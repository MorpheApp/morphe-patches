/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4778
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.interaction.pausewhenmuted

import app.morphe.patcher.patch.patch
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.mediasession.addSetMetadataWithSessionHook
import app.morphe.patches.music.misc.mediasession.addSetPlaybackStateHook
import app.morphe.patches.music.misc.mediasession.mediaSessionHookPatch
import app.morphe.patches.music.misc.settings.PreferenceScreen
import app.morphe.patches.music.misc.settings.settingsPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference

private const val EXTENSION_CLASS = "Lapp/morphe/extension/music/patches/PauseWhenMutedPatch;"

@Suppress("unused")
val pauseWhenMutedPatch = patch(
    name = "Pause when muted",
    description = "Adds an option to pause playback when the system volume is muted, " +
            "and to resume it when the volume is raised."
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        mediaSessionHookPatch
    )

    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)

    execute {
        PreferenceScreen.PLAYER.addPreferences(
            SwitchPreference("morphe_music_pause_when_muted", summary = true)
        )

        // The media session is needed to pause and resume YouTube Music's own player.
        addSetMetadataWithSessionHook(
            "$EXTENSION_CLASS->onSetMetadata(Landroid/media/session/MediaSession;Landroid/media/MediaMetadata;)V"
        )
        addSetPlaybackStateHook("$EXTENSION_CLASS->onSetPlaybackState(Landroid/media/session/PlaybackState;)V")
    }
}
