/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3295
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.video.speed

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.settings.PreferenceScreen
import app.morphe.patches.music.misc.settings.settingsPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.patches.music.video.information.musicVideoInformationPatch
import app.morphe.patches.music.video.information.musicVideoTimeHook
import app.morphe.patches.shared.misc.settings.preference.NonInteractivePreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference.Sorting
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.video.information.addExoPlayerHooks

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/music/patches/PlaybackSpeedPatch;"

@Suppress("unused")
val playbackSpeedPatch = bytecodePatch(
    name = "Playback speed",
    description = "Adds options to change the playback speed and pitch of tracks.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        // Used to apply a changed speed setting while a track is playing.
        musicVideoInformationPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)

    execute {
        PreferenceScreen.PLAYER.addPreferences(
            PreferenceScreenPreference(
                key = "morphe_music_playback_speed_screen",
                sorting = Sorting.UNSORTED,
                preferences = setOf(
                    NonInteractivePreference(
                        key = "morphe_music_playback_speed",
                        tag = "app.morphe.extension.shared.settings.preference.SeekBarPreference",
                        selectable = true
                    ),
                    SwitchPreference("morphe_music_playback_speed_change_pitch", summary = true),
                )
            )
        )

        // region ExoPlayerImpl.

        addExoPlayerHooks(EXTENSION_CLASS)

        // endregion

        // Apply a changed speed setting without waiting for the next track.
        musicVideoTimeHook(EXTENSION_CLASS, "setVideoTime")
    }
}
