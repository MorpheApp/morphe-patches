/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.layout.buttons.overlay

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.all.misc.resources.ResourceType
import app.morphe.patches.all.misc.resources.getResourceId
import app.morphe.patches.all.misc.resources.resourceMappingPatch
import app.morphe.patches.shared.misc.settings.preference.NonInteractivePreference
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.shared.misc.settings.preference.noTitleUnsortedPreferenceCategory
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playservice.is_20_28_or_greater
import app.morphe.patches.youtube.misc.playservice.is_21_35_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patches.youtube.shared.LayoutConstructorFingerprint
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.findFreeRegister
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstructionOrThrow
import app.morphe.util.indexOfFirstResourceIdOrThrow
import app.morphe.util.insertLiteralOverride
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/HidePlayerOverlayButtonsPatch;"

val hidePlayerOverlayButtonsPatch = bytecodePatch(
    name = "Hide player overlay buttons",
    description = "Adds options to hide the player Cast, Autoplay, Captions, Previous & Next buttons," +
            " and to hide or change the opacity of the player control buttons background.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        playerOverlayButtonsSettingsPatch,
        resourceMappingPatch, // Used by fingerprints.
        versionCheckPatch
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        addPlayerOverlayPreferences(
            noTitleUnsortedPreferenceCategory(
                SwitchPreference("morphe_hide_autoplay_button"),
                SwitchPreference("morphe_hide_captions_button"),
                SwitchPreference("morphe_hide_cast_button"),
                SwitchPreference("morphe_hide_collapse_button"),
                SwitchPreference("morphe_hide_fullscreen_button"),
                SwitchPreference("morphe_hide_player_control_buttons_background", summary = true),
                SwitchPreference("morphe_hide_player_previous_next_buttons"),
                SwitchPreference("morphe_hide_settings_button"),
            ),
            NonInteractivePreference(
                key = "morphe_player_control_buttons_background_opacity",
                tag = "app.morphe.extension.shared.settings.preference.SeekBarPreference",
            )
        )

        // region Hide player previous/next & settings button.

        LayoutConstructorFingerprint.let {
            it.clearMatch() // Fingerprint is shared with other patches.

            // Verify resources exist.
            getResourceId(ResourceType.ID, "player_control_next_button_touch_area")
            getResourceId(ResourceType.ID, "player_control_previous_button_touch_area")
            getResourceId(ResourceType.ID, "player_overflow_button")
            getResourceId(ResourceType.ID, "media_route_button")

            it.method.apply {
                val insertIndex = it.instructionMatches.last().index
                val viewRegister = getInstruction<FiveRegisterInstruction>(insertIndex).registerC

                addInstructions(
                    insertIndex,
                    """
                        invoke-static { v$viewRegister }, $EXTENSION_CLASS->hideCastButton(Landroid/view/View;)V
                        invoke-static { v$viewRegister }, $EXTENSION_CLASS->hidePreviousNextButtons(Landroid/view/View;)V
                        invoke-static { v$viewRegister }, $EXTENSION_CLASS->hideSettingsButton(Landroid/view/View;)V
                    """
                )
            }
        }

        // endregion

        // region Hide cast button.

        if (is_21_35_or_greater) {
            OverlayCastButtonVisibilityFingerprint.let {
                it.method.apply {
                    var instructionIndex = it.instructionMatches.first().index
                    var instructionRegister = getInstruction<FiveRegisterInstruction>(
                        instructionIndex
                    ).registerD

                    addInstructions(
                        instructionIndex,
                        """
                            invoke-static { v$instructionRegister }, $EXTENSION_CLASS->hideCastButton(I)I
                            move-result v$instructionRegister
                        """
                    )
                }
            }
        } else {
            OverlayCastButtonVisibilityLegacyFingerprint.let {
                it.method.apply {
                    val index = it.instructionMatches.first().index
                    val visibilityRegister =
                        getInstruction<FiveRegisterInstruction>(index).registerD
                    val visibilityMethodType =
                        it.instructionMatches.first().getMethodCalled().parameterTypes.last()

                    addInstructionsAtControlFlowLabel(
                        index,
                        """
                            invoke-static { v$visibilityRegister }, $EXTENSION_CLASS->hideCastButton($visibilityMethodType)$visibilityMethodType
                            move-result v$visibilityRegister
                        """
                    )
                }
            }
        }

        if (is_20_28_or_greater) {
            arrayOf(
                CastButtonPlayerFeatureFlagFingerprint,
                CastButtonActionFeatureFlagFingerprint // Cast button in the feed.
            ).forEach { fingerprint ->
                fingerprint.matchAll().forEach {
                    it.method.insertLiteralOverride(
                        it.instructionMatches.first().index,
                        "$EXTENSION_CLASS->hideCastButton(Z)Z"
                    )
                }
            }
        }

        // endregion

        // region Hide captions button.

        SubtitleButtonControllerFingerprint.let {
            it.method.apply {
                val viewIndex = it.instructionMatches.first().index
                val viewRegister = getInstruction<TwoRegisterInstruction>(viewIndex).registerA

                addInstruction(
                    viewIndex + 1,
                    "invoke-static { v$viewRegister }, $EXTENSION_CLASS->" +
                            "hideCaptionsButton(Landroid/widget/ImageView;)V",
                )
            }
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
                    invoke-static {}, $EXTENSION_CLASS->hideAutoplayButton()Z
                    move-result v$constRegister
                    if-nez v$constRegister, :hidden
                """,
                ExternalLabel("hidden", getInstruction(gotoIndex)),
            )
        }

        // endregion

        // region Hide collapse button.

        TitleAnchorFingerprint.let {
            it.method.apply {
                val titleAnchorIndex = it.instructionMatches.last().index
                val titleAnchorRegister = getInstruction<OneRegisterInstruction>(titleAnchorIndex).registerA

                addInstruction(
                    titleAnchorIndex + 1,
                    "invoke-static { v$titleAnchorRegister }, $EXTENSION_CLASS->setTitleAnchorStartMargin(Landroid/view/View;)V"
                )

                val playerCollapseButtonIndex = it.instructionMatches[1].index
                val playerCollapseButtonRegister = getInstruction<OneRegisterInstruction>(playerCollapseButtonIndex).registerA

                addInstruction(
                    playerCollapseButtonIndex + 1,
                    "invoke-static { v$playerCollapseButtonRegister }, $EXTENSION_CLASS->hideCollapseButton(Landroid/widget/ImageView;)V"
                )
            }
        }

        // endregion

        // region Hide fullscreen button.

        FullscreenButtonFingerprint.let {
            it.method.apply {
                val castIndex = it.instructionMatches[1].index
                val insertIndex = castIndex + 1
                val insertRegister = getInstruction<OneRegisterInstruction>(castIndex).registerA

                addInstructionsWithLabels(
                    insertIndex,
                    """
                        invoke-static { v$insertRegister }, $EXTENSION_CLASS->hideFullscreenButton(Landroid/widget/ImageView;)Landroid/widget/ImageView;
                        move-result-object v$insertRegister
                        if-nez v$insertRegister, :show
                        return-void
                    """,
                    ExternalLabel("show", getInstruction(insertIndex))
                )
            }
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
                        # 21.21+ now uses the returned inflated view but the changes here still work.
                        move-result-object v$freeRegister
                        invoke-static { v$freeRegister }, $EXTENSION_CLASS->styleControlButtonsBackground(Landroid/view/View;)Landroid/view/View;
                    """
                )
            }
        }

        // endregion
    }
}
