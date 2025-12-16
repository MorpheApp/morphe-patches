package app.morphe.patches.youtube.interaction.seekbar

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playservice.is_19_17_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.util.findInstructionIndicesReversed
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val EXTENSION_CLASS_DESCRIPTOR = "Lapp/morphe/extension/youtube/patches/SlideToSeekPatch;"

val enableSlideToSeekPatch = bytecodePatch(
    description = "Adds an option to enable slide to seek " +
        "instead of playing at 2x speed when pressing and holding in the video player."
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        versionCheckPatch,
    )

    execute {
        PreferenceScreen.SEEKBAR.addPreferences(
            SwitchPreference("morphe_slide_to_seek"),
        )

        var modifiedMethods = false

        // Restore the behaviour to slide to seek.

        val checkIndex = SlideToSeekFingerprint.instructionMatches.first().index
        val checkReference = SlideToSeekFingerprint.method.getInstruction(checkIndex)
            .getReference<MethodReference>()!!

        val extensionMethodDescriptor = "$EXTENSION_CLASS_DESCRIPTOR->isSlideToSeekDisabled(Z)Z"

        // A/B check method was only called on this class.
        SlideToSeekFingerprint.classDef.methods.forEach { method ->
            method.findInstructionIndicesReversed {
                opcode == Opcode.INVOKE_VIRTUAL && getReference<MethodReference>() == checkReference
            }.forEach { index ->
                method.apply {
                    val register = getInstruction<OneRegisterInstruction>(index + 1).registerA

                    addInstructions(
                        index + 2,
                        """
                            invoke-static { v$register }, $extensionMethodDescriptor
                            move-result v$register
                       """,
                    )
                }

                modifiedMethods = true
            }
        }

        if (!modifiedMethods) throw PatchException("Could not find methods to modify")

        // Disable the double speed seek gesture.
        if (is_19_17_or_greater) {
            DisableFastForwardGestureFingerprint.let {
                it.method.apply {
                    val targetIndex = it.instructionMatches.last().index
                    val targetRegister = getInstruction<OneRegisterInstruction>(targetIndex).registerA

                    addInstructions(
                        targetIndex + 1,
                        """
                            invoke-static { v$targetRegister }, $extensionMethodDescriptor
                            move-result v$targetRegister
                        """,
                    )
                }
            }
        } else {
            DisableFastForwardLegacyFingerprint.let {
                it.method.apply {
                    val insertIndex = it.instructionMatches.last().index + 1
                    val targetRegister = getInstruction<OneRegisterInstruction>(insertIndex).registerA

                    addInstructions(
                        insertIndex,
                        """
                            invoke-static { v$targetRegister }, $extensionMethodDescriptor
                            move-result v$targetRegister
                        """,
                    )
                }
            }
        }
    }
}
