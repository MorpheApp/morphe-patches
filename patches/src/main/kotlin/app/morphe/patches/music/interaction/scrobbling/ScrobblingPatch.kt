/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.patches.music.interaction.scrobbling

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.settings.PreferenceScreen
import app.morphe.patches.music.misc.settings.settingsPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.patches.shared.misc.settings.preference.NonInteractivePreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceCategory
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction

private const val EXTENSION_CLASS = "Lapp/morphe/extension/music/patches/scrobbling/ScrobbleHook;"

@Suppress("unused")
val scrobblingPatch = bytecodePatch(
    name = "Scrobbling support",
    description = "Enables scrobbling played tracks to ListenBrainz and Last.fm.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch
    )

    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)

    execute {
        val listenBrainzCategory = PreferenceCategory(
            key = "morphe_settings_music_listenbrainz",
            preferences = setOf(
                NonInteractivePreference(
                    key = "morphe_music_listenbrainz_token_ui",
                    titleKey = "morphe_music_listenbrainz_token_title",
                    summaryKey = null,
                    tag = "app.morphe.extension.music.settings.preference.ListenBrainzTokenPreference",
                    selectable = true
                ),
                SwitchPreference("morphe_music_listenbrainz_enabled"),
                SwitchPreference("morphe_music_listenbrainz_now_playing"),
                NonInteractivePreference(
                    key = "morphe_music_listenbrainz_min_song_duration",
                    summaryKey = null,
                    tag = "app.morphe.extension.shared.settings.preference.SeekBarPreference",
                    selectable = true
                ),
                NonInteractivePreference(
                    key = "morphe_music_listenbrainz_delay_percent",
                    summaryKey = null,
                    tag = "app.morphe.extension.shared.settings.preference.SeekBarPreference",
                    selectable = true
                ),
                NonInteractivePreference(
                    key = "morphe_music_listenbrainz_delay_seconds",
                    summaryKey = null,
                    tag = "app.morphe.extension.shared.settings.preference.SeekBarPreference",
                    selectable = true
                )
            )
        )

        val lastfmCategory = PreferenceCategory(
            key = "morphe_settings_music_lastfm",
            preferences = setOf(
                NonInteractivePreference(
                    key = "morphe_music_lastfm_token_ui",
                    titleKey = "morphe_music_lastfm_token_title",
                    summaryKey = null,
                    tag = "app.morphe.extension.music.settings.preference.LastFMTokenPreference",
                    selectable = true
                ),
                SwitchPreference("morphe_music_lastfm_enabled"),
                SwitchPreference("morphe_music_lastfm_now_playing"),
                NonInteractivePreference(
                    key = "morphe_music_lastfm_min_song_duration",
                    summaryKey = null,
                    tag = "app.morphe.extension.shared.settings.preference.SeekBarPreference",
                    selectable = true
                ),
                NonInteractivePreference(
                    key = "morphe_music_lastfm_delay_percent",
                    summaryKey = null,
                    tag = "app.morphe.extension.shared.settings.preference.SeekBarPreference",
                    selectable = true
                ),
                NonInteractivePreference(
                    key = "morphe_music_lastfm_delay_seconds",
                    summaryKey = null,
                    tag = "app.morphe.extension.shared.settings.preference.SeekBarPreference",
                    selectable = true
                )
            )
        )

        PreferenceScreen.SCROBBLING.addPreferences(
            listenBrainzCategory,
            lastfmCategory
        )

        MediaSessionSetPlaybackStateFingerprint.let {
            it.method.apply {
                it.instructionMatches.reversed().forEach { match ->
                    val index = match.index
                    val register = getInstruction<FiveRegisterInstruction>(index).registerD
                    addInstruction(
                        index,
                        "invoke-static { v$register }, $EXTENSION_CLASS->onSetPlaybackState(Landroid/media/session/PlaybackState;)V"
                    )
                }
            }
        }

        MediaSessionSetMetadataFingerprint.let {
            it.method.apply {
                it.instructionMatches.reversed().forEach { match ->
                    val index = match.index
                    val register = getInstruction<FiveRegisterInstruction>(index).registerD
                    addInstruction(
                        index,
                        "invoke-static { v$register }, $EXTENSION_CLASS->onSetMetadata(Landroid/media/MediaMetadata;)V"
                    )
                }
            }
        }
    }
}
