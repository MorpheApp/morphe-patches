/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.video.volume

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch

internal const val PLAYER_VOLUME_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/youtube/patches/PlayerVolumePatch;"

// Scales the video playback volume at the ExoPlayer audio sink.
internal val playerVolumeHookPatch = bytecodePatch(
    description = "Hooks AudioSink setVolume and AudioTrack wrapper constructor to adjust video playback volume."
) {
    dependsOn(sharedExtensionPatch)

    execute {
        // Modifies the volume value passed into the public AudioSink setVolume;
        AudioSinkSetVolumeFingerprint.method.addInstructions(
            0,
            """
                invoke-static { p1 }, $PLAYER_VOLUME_CLASS_DESCRIPTOR->getAudioMultiplier(F)F
                move-result p1
            """
        )

        // Captures the AudioTrack reference for immediate re-application.
        AudioTrackWrapperInitFingerprint.method.addInstruction(
            0,
            "invoke-static { p1 }, $PLAYER_VOLUME_CLASS_DESCRIPTOR->setAudioTrack(Landroid/media/AudioTrack;)V"
        )
    }
}
