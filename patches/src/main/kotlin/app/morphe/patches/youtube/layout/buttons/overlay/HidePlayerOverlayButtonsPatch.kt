package app.morphe.patches.youtube.layout.buttons.overlay

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.shared.misc.mapping.resourceMappingPatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playservice.is_20_28_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.LayoutConstructorFingerprint
import app.morphe.patches.youtube.shared.SubtitleButtonControllerFingerprint
import app.morphe.util.findFreeRegister
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstructionOrThrow
import app.morphe.util.indexOfFirstResourceIdOrThrow
import app.morphe.util.insertLiteralOverride
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/youtube/patches/HidePlayerOverlayButtonsPatch;"

val hidePlayerOverlayButtonsPatch = bytecodePatch(
    name = "Hide player overlay buttons",
    description = "Adds options to hide the player Cast, Autoplay, Captions, Previous & Next buttons, and the player " +
        "control buttons background.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        resourceMappingPatch, // Used by fingerprints.
        versionCheckPatch
    )

    compatibleWith(
        "com.google.android.youtube"(
            "20.14.43",
            "20.21.37",
            "20.31.42",
            "20.46.41",
        )
    )

    execute {
        PreferenceScreen.PLAYER.addPreferences(
            SwitchPreference("morphe_hide_player_previous_next_buttons"),
            SwitchPreference("morphe_hide_cast_button"),
            SwitchPreference("morphe_hide_captions_button"),
            SwitchPreference("morphe_hide_autoplay_button"),
            SwitchPreference("morphe_hide_player_control_buttons_background"),
        )

        // region Hide player next/previous button.

        LayoutConstructorFingerprint.let {
            it.clearMatch() // Fingerprint is shared with other patches.

            it.method.apply {
                val insertIndex = it.instructionMatches.last().index
                val viewRegister = getInstruction<FiveRegisterInstruction>(insertIndex).registerC

                addInstruction(
                    insertIndex,
                    "invoke-static { v$viewRegister }, $EXTENSION_CLASS_DESCRIPTOR" +
                            "->hidePreviousNextButtons(Landroid/view/View;)V",
                )
            }
        }

        // endregion

        // region Hide cast button.

        MediaRouteButtonFingerprint.method.addInstructions(
            0,
            """
                invoke-static { p1 }, $EXTENSION_CLASS_DESCRIPTOR->getCastButtonOverrideV2(I)I
                move-result p1
            """
        )

        if (is_20_28_or_greater) {
            arrayOf(
                CastButtonPlayerFeatureFlagFingerprint,
                CastButtonActionFeatureFlagFingerprint
            ).forEach { fingerprint ->
                fingerprint.let {
                    it.method.insertLiteralOverride(
                        it.instructionMatches.first().index,
                        "$EXTENSION_CLASS_DESCRIPTOR->getCastButtonOverrideV2(Z)Z"
                    )
                }
            }
        }

        // endregion

        // region Hide captions button.

        SubtitleButtonControllerFingerprint.method.apply {
            val insertIndex = indexOfFirstInstructionOrThrow(Opcode.IGET_BOOLEAN) + 1

            addInstruction(
                insertIndex,
                "invoke-static { v0 }, $EXTENSION_CLASS_DESCRIPTOR->hideCaptionsButton(Landroid/widget/ImageView;)V",
            )
        }

        // endregion

        // region Hide autoplay button.

        LayoutConstructorFingerprint.method.apply {
            val constIndex = indexOfFirstResourceIdOrThrow("autonav_toggle")
            val constRegister = getInstruction<OneRegisterInstruction>(constIndex).registerA

            // Add a conditional branch around the code that inflates and adds the auto-repeat button.
            val gotoIndex = indexOfFirstInstructionOrThrow(constIndex) {
                val parameterTypes = getReference<MethodReference>()?.parameterTypes
                opcode == Opcode.INVOKE_VIRTUAL &&
                    parameterTypes?.size == 2 &&
                    parameterTypes.first() == "Landroid/view/ViewStub;"
            } + 1

            addInstructionsWithLabels(
                constIndex,
                """
                    invoke-static {}, $EXTENSION_CLASS_DESCRIPTOR->hideAutoPlayButton()Z
                    move-result v$constRegister
                    if-nez v$constRegister, :hidden
                """,
                ExternalLabel("hidden", getInstruction(gotoIndex)),
            )
        }

        // endregion

        // region Hide player control buttons background.

        InflateControlsGroupLayoutStubFingerprint.let {
            it.method.apply {
                val insertIndex = it.instructionMatches.last().index + 1
                val freeRegister = findFreeRegister(insertIndex)

                addInstructions(
                    insertIndex,
                    """
                        # Move the inflated layout to a temporary register.
                        # The result of the inflate method is by default not moved to a register after the method is called.
                        move-result-object v$freeRegister
                        invoke-static { v$freeRegister }, $EXTENSION_CLASS_DESCRIPTOR->hidePlayerControlButtonsBackground(Landroid/view/View;)V
                    """
                )
            }
        }

        // endregion
    }
}
