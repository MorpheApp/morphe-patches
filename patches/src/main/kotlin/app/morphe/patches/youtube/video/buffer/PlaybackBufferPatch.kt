/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3386
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.video.buffer

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction

private const val PLAYBACK_BUFFER_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/youtube/patches/PlaybackBufferPatch;"

@Suppress("unused")
val playbackBufferPatch = bytecodePatch(
    name = "Playback buffer",
    description = "Adds an option to change the video playback buffer size."
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.VIDEO.addPreferences(
            ListPreference("morphe_playback_buffer_size")
        )

        ShouldContinueLoadingFingerprint.let {
            it.method.apply {
                val bufferedIndex = it.instructionMatches.first().index
                val register = getInstruction<TwoRegisterInstruction>(bufferedIndex).registerA

                addInstructions(
                    bufferedIndex + 1,
                    """
                        invoke-static/range { v$register .. v${register + 1} }, $PLAYBACK_BUFFER_CLASS_DESCRIPTOR->scaleBufferedDurationUs(J)J
                        move-result-wide v$register
                    """
                )
            }
        }

        TracksSelectedFingerprint.let {
            it.method.apply {
                val limitIndex = it.instructionMatches.last().index
                val register = getInstruction<TwoRegisterInstruction>(limitIndex).registerA

                addInstructions(
                    limitIndex + 1,
                    """
                        invoke-static/range { v$register .. v$register }, $PLAYBACK_BUFFER_CLASS_DESCRIPTOR->scaleByteLimit(I)I
                        move-result v$register
                    """
                )
            }
        }
    }
}
