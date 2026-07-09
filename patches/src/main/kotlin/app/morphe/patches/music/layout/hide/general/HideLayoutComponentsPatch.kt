/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.layout.hide.general

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.litho.filter.lithoFilterPatch
import app.morphe.patches.music.misc.settings.PreferenceScreen
import app.morphe.patches.music.misc.settings.settingsPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.patches.shared.misc.litho.filter.addLithoFilter
import app.morphe.patches.shared.misc.settings.preference.InputType
import app.morphe.patches.shared.misc.settings.preference.PreferenceCategory
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference.Sorting
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.shared.misc.settings.preference.TextPreference

private const val LAYOUT_COMPONENTS_FILTER =
    "Lapp/morphe/extension/music/patches/components/LayoutComponentsFilter;"
private const val CUSTOM_FILTER =
    "Lapp/morphe/extension/music/patches/components/CustomFilter;"

@Suppress("unused")
val hideLayoutComponentsPatch = bytecodePatch(
    name = "Hide layout components",
    description = "Adds options to hide general layout components."
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        lithoFilterPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)

    execute {
        PreferenceScreen.PLAYER.addPreferences(
            PreferenceCategory(
                titleKey = "morphe_music_hide_lyrics_panel_category_title",
                preferences = setOf(
                    SwitchPreference("morphe_music_hide_lyrics_share_button"),
                    SwitchPreference("morphe_music_hide_lyrics_translate_button")
                )
            )
        )

        PreferenceScreen.GENERAL.addPreferences(
            PreferenceScreenPreference(
                key = "morphe_music_custom_filter_screen",
                titleKey = "morphe_custom_filter_screen_title",
                summaryKey = "morphe_custom_filter_screen_summary",
                sorting = Sorting.UNSORTED,
                preferences = setOf(
                    SwitchPreference(
                        key = "morphe_music_custom_filter",
                        titleKey = "morphe_custom_filter_title"
                    ),
                    TextPreference(
                        key = "morphe_music_custom_filter_strings",
                        titleKey = "morphe_custom_filter_strings_title",
                        summaryKey = "morphe_custom_filter_strings_summary",
                        inputType = InputType.TEXT_MULTI_LINE
                    )
                )
            )
        )

        addLithoFilter(LAYOUT_COMPONENTS_FILTER)
        addLithoFilter(CUSTOM_FILTER)
    }
}
