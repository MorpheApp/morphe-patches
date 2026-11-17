/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.interaction.likefilter

import app.morphe.patcher.patch.patch
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.settings.PreferenceScreen
import app.morphe.patches.music.misc.settings.settingsPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.patches.music.video.information.musicVideoIdHook
import app.morphe.patches.music.video.information.musicVideoInformationPatch
import app.morphe.patches.music.video.playerresponse.Hook
import app.morphe.patches.music.video.playerresponse.addPlayerResponseMethodHook
import app.morphe.patches.music.video.playerresponse.musicPlayerResponseMethodHookPatch
import app.morphe.patches.shared.misc.settings.preference.InputType
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.shared.misc.settings.preference.TextPreference
import app.morphe.patches.shared.misc.settings.preference.noTitleUnsortedPreferenceCategory

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/music/patches/QueueLikeFilterPatch;"

val queueLikeFilterPatch = patch(
    name = "Queue likes filter",
    description = "Adds options to remove low-like songs from auto-generated mixes and radio " +
            "queues. User playlists are left unchanged.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        musicVideoInformationPatch,
        musicPlayerResponseMethodHookPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)

    execute {
        PreferenceScreen.PLAYER.addPreferences(
            // Keep the toggle directly above the minimum, even though the Player screen
            // otherwise sorts its preferences by title.
            noTitleUnsortedPreferenceCategory(
                SwitchPreference("morphe_music_queue_like_filter_enabled", summary = true),
                TextPreference(
                    key = "morphe_music_queue_like_filter_min_likes",
                    inputType = InputType.NUMBER,
                ),
            ),
        )

        installQueueLikeFilter(resolveQueueLikeFilterAbi())

        // Wake the worker whenever playback moves to another track.
        musicVideoIdHook("$EXTENSION_CLASS->onVideoIdChanged(Ljava/lang/String;)V")

        // Also track the playlist source, so user playlists and albums are left untouched.
        addPlayerResponseMethodHook(
            Hook.VideoIdAndPlaylistId(
                "$EXTENSION_CLASS->onPlaylistChanged(Ljava/lang/String;Ljava/lang/String;I)V"
            )
        )
    }
}
