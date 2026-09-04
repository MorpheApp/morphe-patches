/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2269
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.layout.lyrics

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.all.misc.resources.addResourcesPatch
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.litho.filter.lithoFilterPatch
import app.morphe.patches.music.misc.settings.PreferenceScreen
import app.morphe.patches.music.misc.settings.settingsPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.patches.music.shared.MediaSessionSetMetadataFingerprint
import app.morphe.patches.music.shared.hookMediaSessionArgument
import app.morphe.patches.music.video.information.musicVideoInformationPatch
import app.morphe.patches.shared.MediaSessionSetPlaybackStateFingerprint
import app.morphe.patches.shared.misc.litho.filter.addLithoFilter
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.patches.shared.misc.settings.preference.NonInteractivePreference
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.util.ResourceGroup
import app.morphe.util.copyResources

private const val EXTENSION_CLASS = "Lapp/morphe/extension/music/patches/lyrics/LyricsPatch;"

private const val LYRICS_PANEL_FILTER =
    "Lapp/morphe/extension/music/patches/components/LyricsPanelFilter;"

@Suppress("unused")
val lyricsPatch = bytecodePatch(
    name = "Third-party lyrics",
    description = "Adds an option to show synced lyrics from LRCLIB or KuGou in the lyrics panel."
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        addResourcesPatch,
        lithoFilterPatch,
        musicVideoInformationPatch,
        // The copy button needs its icon whether or not the patch that owns
        // these resources is applied.
        resourcePatch {
            execute {
                copyResources(
                    "copyvideolinkbutton",
                    ResourceGroup("drawable", "morphe_yt_copy_bold.xml")
                )
            }
        }
    )

    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)

    execute {
        PreferenceScreen.LYRICS.addPreferences(
            SwitchPreference("morphe_music_lyrics_enabled", summary = true),
            ListPreference("morphe_music_lyrics_source"),
            SwitchPreference("morphe_music_lyrics_tap_to_seek", summary = true),
            SwitchPreference("morphe_music_lyrics_show_copy_button"),
            SwitchPreference("morphe_music_lyrics_show_translate_button"),
            NonInteractivePreference(
                key = "morphe_music_lyrics_text_size",
                summaryKey = "morphe_music_lyrics_text_size_summary",
                tag = "app.morphe.extension.shared.settings.preference.SeekBarPreference",
                selectable = true
            ),
            NonInteractivePreference(
                key = "morphe_music_lyrics_offset_ms",
                summaryKey = "morphe_music_lyrics_offset_ms_summary",
                tag = "app.morphe.extension.shared.settings.preference.SeekBarPreference",
                selectable = true
            ),
            NonInteractivePreference(
                key = "morphe_music_lyrics_about",
                titleKey = "morphe_music_lyrics_about_title",
                summaryKey = "morphe_music_lyrics_about_summary"
            )
        )

        // The panel content is built by Elements, so there is no view to hook. The timed
        // lyrics component is the earliest signal that the opened panel is the lyrics one.
        addLithoFilter(LYRICS_PANEL_FILTER)

        MediaSessionSetMetadataFingerprint.hookMediaSessionArgument(
            "$EXTENSION_CLASS->onSetMetadata(Landroid/media/MediaMetadata;)V"
        )

        MediaSessionSetPlaybackStateFingerprint.hookMediaSessionArgument(
            "$EXTENSION_CLASS->onSetPlaybackState(Landroid/media/session/PlaybackState;)V"
        )
    }
}
