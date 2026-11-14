package app.morphe.patches.youtube.interaction.seekbar

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.patch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patcher.util.findFreeRegister
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val EXTENSION_CLASS = "Lapp/morphe/extension/youtube/patches/TapToSeekPatch;"

val enableTapToSeekPatch = patch(
    description = "Adds an option to enable tap to seek on the seekbar of the video player.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
    )

    execute {
        PreferenceScreen.SEEKBAR.addPreferences(
            SwitchPreference("morphe_tap_to_seek", summary = true),
        )

        // Find the required methods to tap the seekbar.
        val tapToSeekMethods = OnTouchEventHandlerFingerprint.let {
            fun getReference(index: Int) = it.method.getInstruction<ReferenceInstruction>(index)
                .reference as MethodReference

            listOf(
                getReference(it.instructionMatches.first().index),
                getReference(it.instructionMatches.last().index)
            )
        }

        TapToSeekFingerprint.let {
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

                val oMethod = tapToSeekMethods[0]
                val nMethod = tapToSeekMethods[1]

                addInstructionsWithLabels(
                    insertIndex,
                    """
                        invoke-static { }, $EXTENSION_CLASS->tapToSeekEnabled()Z
                        move-result v$freeRegister
                        if-eqz v$freeRegister, :disabled
                        invoke-virtual { v$thisInstanceRegister, v$xAxisRegister }, $oMethod
                        invoke-virtual { v$thisInstanceRegister, v$xAxisRegister }, $nMethod
                        :disabled
                        nop
                    """
                )
            }
        }

        // A tap opens the scrub when the finger touches the time bar, and the scrub is closed
        // when the finger is lifted. The time bar ignores the touch events while it is disabled,
        // such as while the video length is unknown, so a tap left the scrub open. Then the
        // player kept acting as if the time bar was still scrubbed, for example the "More videos"
        // button in fullscreen did nothing until the device was rotated.
        // The scrub is completed the same way the time bar does when the player controls are hidden.
        TimeBarCompleteHiddenScrubFingerprint.let {
            val isScrubbingMethod = it.instructionMatches.first().methodReference
            val completeScrubMethod = it.instructionMatches[3].methodReference

            it.method.addInstructionsWithLabels(
                0,
                """
                    invoke-static { p0, p1 }, $EXTENSION_CLASS->shouldCompleteScrub(Landroid/view/View;Landroid/view/MotionEvent;)Z
                    move-result v0
                    if-eqz v0, :ignore
                    invoke-virtual { p0 }, $isScrubbingMethod
                    move-result v0
                    if-eqz v0, :ignore
                    invoke-virtual { p0 }, $completeScrubMethod
                    :ignore
                    nop
                """
            )
        }
    }
}
