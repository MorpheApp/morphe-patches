/*
 * Apple Music animated artwork integration for Morphe.
 *
 * The runtime implementation is adapted from the standalone YTM-AppleArtwork
 * project by Archimetrix. Keep the original project license and notices.
 */
package app.morphe.patches.music.misc.applemusic

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.patch
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.mediasession.addSetMetadataHook
import app.morphe.patches.music.misc.mediasession.addSetPlaybackStateHook
import app.morphe.patches.music.misc.mediasession.mediaSessionHookPatch
import app.morphe.patches.music.misc.settings.PreferenceScreen
import app.morphe.patches.music.misc.settings.settingsPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.patches.music.shared.MusicActivityOnCreateFingerprint
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/music/patches/applemusic/AppleMusicArtwork;"

@Suppress("unused")
val appleMusicArtworkPatch = patch(
    name = "Apple Music animated artwork",
    description = "Displays Apple Music animated album artwork in the YouTube Music player."
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        mediaSessionHookPatch
    )

    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)

    execute {
        PreferenceScreen.PLAYER.addPreferences(
            SwitchPreference(
                key = "morphe_apple_music_artwork_enabled",
                summary = true
            )
        )

        MusicActivityOnCreateFingerprint.method.addInstruction(
            0,
            "invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS->onActivityCreate(Landroid/app/Activity;)V"
        )

        addSetMetadataHook(
            "$EXTENSION_CLASS->onMetadata(Landroid/media/MediaMetadata;)V"
        )
        addSetPlaybackStateHook(
            "$EXTENSION_CLASS->onPlaybackState(Landroid/media/session/PlaybackState;)V"
        )
    }
}
