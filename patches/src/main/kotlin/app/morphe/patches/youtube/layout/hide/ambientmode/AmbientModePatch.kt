package app.morphe.patches.youtube.layout.hide.ambientmode

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstructionOrThrow
import app.morphe.util.indexOfFirstInstructionReversedOrThrow
import app.morphe.util.indexOfFirstStringInstructionOrThrow
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.Opcode

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/youtube/patches/AmbientModePatch;"

@Suppress("unused")
val ambientModePatch = bytecodePatch(
    name = "Ambient mode",
    description = "Adds options to bypass power saving restrictions for Ambient mode and disable it entirely or in fullscreen.",
) {
    dependsOn(
        settingsPatch,
        sharedExtensionPatch,
    )

    compatibleWith(
        "com.google.android.youtube"(
            "20.14.43",
            "20.21.37",
            "20.26.46",
            "20.31.42",
            "20.37.48",
            "20.40.45",
        )
    )

    execute {
        PreferenceScreen.PLAYER.addPreferences(
            PreferenceScreenPreference(
                key = "morphe_ambient_mode_screen",
                sorting = PreferenceScreenPreference.Sorting.UNSORTED,
                preferences = setOf(
                    SwitchPreference("morphe_bypass_ambient_mode_restrictions"),
                    SwitchPreference("morphe_disable_ambient_mode"),
                    SwitchPreference("morphe_disable_fullscreen_ambient_mode"),
                )
            )
        )

        //
        // Bypass Ambient mode restrictions.
        //
        PowerSaveModeReceiverFingerprint.methodOrNull?.apply {
            val instructions = implementation?.instructions ?: return@apply

            val callIndex = instructions
                .withIndex()
                .firstOrNull { (_, instruction) ->
                    val ref = (instruction as? ReferenceInstruction)?.reference
                    instruction.opcode in setOf(
                        Opcode.INVOKE_VIRTUAL,
                        Opcode.INVOKE_INTERFACE,
                    ) &&
                            ref is MethodReference &&
                            ref.definingClass == "Landroid/os/PowerManager;" &&
                            ref.name == "isPowerSaveMode"
                }
                ?.index
                ?: return@apply

            val moveResultIndex = indexOfFirstInstructionOrThrow(callIndex, Opcode.MOVE_RESULT)
            val register = getInstruction<OneRegisterInstruction>(moveResultIndex).registerA

            addInstructions(
                moveResultIndex + 1,
                """
        invoke-static {v$register}, $EXTENSION_CLASS_DESCRIPTOR->bypassAmbientModeRestrictions(Z)Z
        move-result v$register
        """
            )
        }

        //
        // Disable fullscreen ambient mode.
        //
        SetFullScreenBackgroundColorFingerprint.method.apply {
            val insertIndex = indexOfFirstInstructionReversedOrThrow {
                getReference<MethodReference>()?.name == "setBackgroundColor"
            }
            val register = getInstruction<FiveRegisterInstruction>(insertIndex).registerD

            addInstructions(
                insertIndex,
                """
                    invoke-static { v$register }, $EXTENSION_CLASS_DESCRIPTOR->getFullScreenBackgroundColor(I)I
                    move-result v$register
                """,
            )
        }
    }
}
