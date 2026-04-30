package app.morphe.patches.youtube.misc.medianotification

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/MediaNotificationControlsPatch;"

val mediaNotificationControlsPatch = bytecodePatch(
    name = "Media notification controls",
    description = "Adds options to disable the seekbar and next/previous buttons in the media notification and headphone controls.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        // Intercept every setPlaybackState call site so all playback states
        // (playing, paused, buffering) have their action flags filtered.
        MediaSessionSetPlaybackStateFingerprint.matchAll().forEach { match ->
            match.method.apply {
                val matchIndex = match.instructionMatches.first().index
                // invoke-virtual {vC=session, vD=playbackState} — vD is the argument to filter.
                val playbackStateReg = getInstruction<FiveRegisterInstruction>(matchIndex).registerD

                addInstructions(
                    matchIndex,
                    """
                        invoke-static { v$playbackStateReg }, $EXTENSION_CLASS->filterPlaybackState(Landroid/media/session/PlaybackState;)Landroid/media/session/PlaybackState;
                        move-result-object v$playbackStateReg
                    """,
                )
            }
        }

    }
}
