package app.morphe.patches.youtube.interaction.seekbar

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.util.findFreeRegister
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val EXTENSION_CLASS_DESCRIPTOR = "Lapp/morphe/extension/youtube/patches/SeekbarTappingPatch;"

val enableSeekbarTappingPatch = bytecodePatch(
    description = "Adds an option to enable tap to seek on the seekbar of the video player.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
    )

    execute {
        PreferenceScreen.SEEKBAR.addPreferences(
            SwitchPreference("morphe_seekbar_tapping"),
        )

        // Find the required methods to tap the seekbar.
        val seekbarTappingMethods = OnTouchEventHandlerFingerprint.let {
            fun getReference(index: Int) = it.method.getInstruction<ReferenceInstruction>(index)
                .reference as MethodReference

            listOf(
                getReference(it.instructionMatches.first().index),
                getReference(it.instructionMatches.last().index)
            )
        }

        SeekbarTappingFingerprint.let {
            val insertIndex = it.instructionMatches.last().index + 1

            it.method.apply {
                val thisInstanceRegister = getInstruction<FiveRegisterInstruction>(
                    insertIndex - 1
                ).registerC

                val xAxisRegister = this.getInstruction<FiveRegisterInstruction>(
                    it.instructionMatches[2].index
                ).registerD

                val freeRegister = findFreeRegister(
                    insertIndex, thisInstanceRegister, xAxisRegister
                )

                val oMethod = seekbarTappingMethods[0]
                val nMethod = seekbarTappingMethods[1]

                addInstructionsWithLabels(
                    insertIndex,
                    """
                        invoke-static { }, $EXTENSION_CLASS_DESCRIPTOR->seekbarTappingEnabled()Z
                        move-result v$freeRegister
                        if-eqz v$freeRegister, :disabled
                        invoke-virtual { v$thisInstanceRegister, v$xAxisRegister }, $oMethod
                        invoke-virtual { v$thisInstanceRegister, v$xAxisRegister }, $nMethod
                    """,
                    ExternalLabel("disabled", getInstruction(insertIndex)),
                )
            }
        }
    }
}
