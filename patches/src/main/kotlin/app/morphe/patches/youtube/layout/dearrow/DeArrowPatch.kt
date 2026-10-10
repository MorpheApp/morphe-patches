/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3447
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.layout.dearrow

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.litho.relayout.lithoRelayoutPatch
import app.morphe.patches.shared.misc.settings.preference.InputType
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.patches.shared.misc.settings.preference.NonInteractivePreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceCategory
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference.Sorting
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.shared.misc.settings.preference.TextPreference
import app.morphe.patches.shared.misc.settings.preference.screenInfoPreferenceCategory
import app.morphe.patches.youtube.layout.originaltitles.videoTitlesHookPatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.imageurlhook.addImageURLErrorCallbackHook
import app.morphe.patches.youtube.misc.imageurlhook.addImageURLHook
import app.morphe.patches.youtube.misc.imageurlhook.addImageURLSuccessCallbackHook
import app.morphe.patches.youtube.misc.imageurlhook.cronetImageURLHookPatch
import app.morphe.patches.youtube.misc.navigation.navigationBarHookPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.util.setExtensionIsPatchIncluded

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/dearrow/DeArrowPatch;"

@Suppress("unused")
val deArrowPatch = bytecodePatch(
    name = "DeArrow",
    description = "Adds options to replace video thumbnails and titles using the DeArrow API, " +
            "or replace video thumbnails with image captures from the video."
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        navigationBarHookPatch,
        cronetImageURLHookPatch,
        // Thumbnails that fail to load are loaded again by mounting the Litho views again.
        lithoRelayoutPatch,
        // Titles are replaced by the same hooks that restore the original titles.
        videoTitlesHookPatch
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        fun thumbnailPreference(key: String, titleKey: String) = ListPreference(
            key = key,
            titleKey = titleKey,
            entriesKey = "morphe_dearrow_thumbnail_options_entries",
            entryValuesKey = "morphe_dearrow_thumbnail_options_entry_values"
        )

        PreferenceScreen.DEARROW.addPreferences(
            PreferenceCategory(
                key = "morphe_dearrow_titles_category",
                titleKey = "morphe_dearrow_titles_title",
                sorting = Sorting.UNSORTED,
                preferences = setOf(
                    SwitchPreference("morphe_dearrow_titles_home"),
                    SwitchPreference("morphe_dearrow_titles_subscription"),
                    SwitchPreference("morphe_dearrow_titles_library"),
                    SwitchPreference("morphe_dearrow_titles_player"),
                    SwitchPreference("morphe_dearrow_titles_search"),
                    SwitchPreference("morphe_dearrow_titles_icon", summary = true),
                    SwitchPreference("morphe_dearrow_casual_mode", summary = true),
                    TextPreference("morphe_dearrow_casual_mode_min_votes", inputType = InputType.NUMBER)
                )
            ),
            PreferenceCategory(
                key = "morphe_dearrow_thumbnails_category",
                titleKey = "morphe_dearrow_thumbnails_title",
                sorting = Sorting.UNSORTED,
                preferences = setOf(
                    thumbnailPreference("morphe_dearrow_thumbnail_home", "morphe_dearrow_titles_home_title"),
                    thumbnailPreference("morphe_dearrow_thumbnail_subscription", "morphe_dearrow_titles_subscription_title"),
                    thumbnailPreference("morphe_dearrow_thumbnail_library", "morphe_dearrow_titles_library_title"),
                    thumbnailPreference("morphe_dearrow_thumbnail_player", "morphe_dearrow_titles_player_title"),
                    thumbnailPreference("morphe_dearrow_thumbnail_search", "morphe_dearrow_titles_search_title"),
                    NonInteractivePreference("morphe_dearrow_thumbnail_stills_about"),
                    ListPreference("morphe_dearrow_thumbnail_stills_time")
                )
            ),
            PreferenceCategory(
                key = "morphe_dearrow_general_category",
                titleKey = "morphe_settings_screen_04_general_title",
                sorting = Sorting.UNSORTED,
                preferences = setOf(
                    TextPreference("morphe_dearrow_api_url"),
                    SwitchPreference("morphe_dearrow_connection_toast", summary = true)
                )
            ),
            screenInfoPreferenceCategory(
                key = "morphe_dearrow_about_category",
                preferences = setOf(
                    NonInteractivePreference(
                        "morphe_dearrow_about",
                        titleKey = null,
                        // Custom about preference with link to the DeArrow website.
                        tag = "app.morphe.extension.youtube.settings.preference.DeArrowAboutPreference",
                        selectable = true
                    )
                )
            )
        )

        // Other patches use DeArrow only if this patch is included.
        setExtensionIsPatchIncluded(EXTENSION_CLASS)

        addImageURLHook(EXTENSION_CLASS)
        addImageURLSuccessCallbackHook(EXTENSION_CLASS)
        addImageURLErrorCallbackHook(EXTENSION_CLASS)
    }
}
