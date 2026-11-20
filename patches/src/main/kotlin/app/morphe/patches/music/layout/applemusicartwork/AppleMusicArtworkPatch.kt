/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5082
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.layout.applemusicartwork

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.patch
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.mediasession.addSetMetadataHook
import app.morphe.patches.music.misc.mediasession.addSetPlaybackStateHook
import app.morphe.patches.music.misc.mediasession.mediaSessionHookPatch
import app.morphe.patches.music.misc.settings.PreferenceScreen
import app.morphe.patches.music.misc.settings.settingsPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/music/patches/applemusicartwork/AppleMusicArtworkPatch;"

@Suppress("unused")
val appleMusicArtworkPatch = patch(
    name = "Apple Music animated artwork",
    description = "Adds an option to show the animated album artwork of Apple Music in the player."
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        mediaSessionHookPatch
    )

    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)

    execute {
        PreferenceScreen.PLAYER.addPreferences(
            SwitchPreference("morphe_music_apple_music_artwork", summary = true)
        )

        PlayerThumbnailFingerprint.let {
            it.method.apply {
                val index = it.instructionMatches.last().index
                val register = getInstruction<TwoRegisterInstruction>(index).registerA

                addInstruction(
                    index + 1,
                    "invoke-static/range { v$register .. v$register }, " +
                            "$EXTENSION_CLASS->setPlayerThumbnail(Landroid/widget/ImageView;)V"
                )
            }
        }

        addSetMetadataHook("$EXTENSION_CLASS->onSetMetadata(Landroid/media/MediaMetadata;)V")
        addSetPlaybackStateHook("$EXTENSION_CLASS->onSetPlaybackState(Landroid/media/session/PlaybackState;)V")
    }
}
