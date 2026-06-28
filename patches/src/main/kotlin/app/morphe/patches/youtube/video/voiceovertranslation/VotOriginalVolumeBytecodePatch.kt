/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.patches.youtube.video.voiceovertranslation

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/voiceovertranslation/VotOriginalVolumePatch;"

// Hooks two YouTube ExoPlayer audio-sink methods so the voice-over translation can scale the
// original YouTube audio without relying on system AudioFocus.
val votOriginalVolumeBytecodePatch = bytecodePatch(
    description = "Hooks AudioSink setVolume and AudioTrack wrapper constructor to adjust video playback volume."
) {
    dependsOn(sharedExtensionPatch)

    execute {
        // Modifies the volume value passed into the public AudioSink setVolume;
        AudioSinkSetVolumeFingerprint.method.addInstructions(
            0,
            """
                invoke-static { p1 }, $EXTENSION_CLASS->getAudioMultiplier(F)F
                move-result p1
            """
        )

        // Captures the AudioTrack reference for immediate re-application.
        AudioTrackWrapperInitFingerprint.method.addInstruction(
            0,
            "invoke-static { p1 }, $EXTENSION_CLASS->setAudioTrack(Landroid/media/AudioTrack;)V"
        )
    }
}
