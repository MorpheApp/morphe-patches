/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2556
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.misc.album

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.settings.PreferenceScreen
import app.morphe.patches.music.misc.settings.settingsPatch
import app.morphe.patches.music.misc.spoof.spoofVideoStreamsPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.patches.music.video.playerresponse.Hook
import app.morphe.patches.music.video.playerresponse.addPlayerResponseMethodHook
import app.morphe.patches.music.video.playerresponse.musicPlayerResponseMethodHookPatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/music/patches/album/PlayAlbumSongsPatch;"

@Suppress("unused")
val playAlbumSongsPatch = bytecodePatch(
    name = "Play albums songs",
    description = "Adds an option to play the song version of album tracks instead of music videos."
) {
    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)

    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        musicPlayerResponseMethodHookPatch,
        // The song is played by serving its streams under the video id of the music video,
        // which is only possible while the streams are fetched by the extension.
        spoofVideoStreamsPatch,
    )

    execute {
        PreferenceScreen.MISC.addPreferences(
            SwitchPreference("morphe_music_play_album_songs", summary = true)
        )

        addPlayerResponseMethodHook(
            Hook.VideoIdAndPlaylistId(
                "$EXTENSION_CLASS->newPlayerResponse(Ljava/lang/String;Ljava/lang/String;I)V"
            )
        )

        SharedPreferencesGetBooleanFingerprint.method.addInstructionsWithLabels(
            0,
            """
                invoke-static { p1 }, $EXTENSION_CLASS->ignoreDontPlayMusicVideoSetting(Ljava/lang/String;)Z
                move-result v0
                if-eqz v0, :read
                const/4 v0, 0x0
                return v0
                :read
                nop
            """
        )
    }
}
