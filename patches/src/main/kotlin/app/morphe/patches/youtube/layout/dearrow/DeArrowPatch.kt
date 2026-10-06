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
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.patches.shared.misc.settings.preference.NonInteractivePreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceCategory
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference.Sorting
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.shared.misc.settings.preference.TextPreference
import app.morphe.patches.youtube.layout.originaltitles.restoreOriginalTitlesPatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.imageurlhook.addImageURLErrorCallbackHook
import app.morphe.patches.youtube.misc.imageurlhook.addImageURLHook
import app.morphe.patches.youtube.misc.imageurlhook.addImageURLSuccessCallbackHook
import app.morphe.patches.youtube.misc.imageurlhook.cronetImageURLHookPatch
import app.morphe.patches.youtube.misc.navigation.navigationBarHookPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/dearrow/DeArrowPatch;"

@Suppress("unused")
val deArrowPatch = bytecodePatch(
    name = "DeArrow",
    description = "Adds options to replace video thumbnails and titles using the DeArrow API, " +
            "or replace video thumbnails with image captures from the video.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        navigationBarHookPatch,
        cronetImageURLHookPatch,
        // Thumbnails that fail to load are loaded again by mounting the Litho views again.
        lithoRelayoutPatch,
        // Titles are replaced by the same hooks that restore the original titles.
        restoreOriginalTitlesPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        fun thumbnailPreference(key: String) = ListPreference(
            key = key,
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
                )
            ),
            PreferenceCategory(
                key = "morphe_dearrow_thumbnails_category",
                titleKey = "morphe_dearrow_thumbnails_title",
                sorting = Sorting.UNSORTED,
                preferences = setOf(
                    thumbnailPreference("morphe_dearrow_thumbnail_home"),
                    thumbnailPreference("morphe_dearrow_thumbnail_subscription"),
                    thumbnailPreference("morphe_dearrow_thumbnail_library"),
                    thumbnailPreference("morphe_dearrow_thumbnail_player"),
                    thumbnailPreference("morphe_dearrow_thumbnail_search"),
                    TextPreference("morphe_dearrow_api_url"),
                    NonInteractivePreference("morphe_dearrow_thumbnail_stills_about"),
                    ListPreference("morphe_dearrow_thumbnail_stills_time"),
                )
            ),
            PreferenceCategory(
                key = "morphe_dearrow_about_category",
                sorting = Sorting.UNSORTED,
                preferences = setOf(
                    NonInteractivePreference(
                        "morphe_dearrow_about",
                        // Custom about preference with link to the DeArrow website.
                        tag = "app.morphe.extension.youtube.settings.preference.DeArrowAboutPreference",
                        selectable = true,
                    ),
                    SwitchPreference("morphe_dearrow_connection_toast", summary = true),
                )
            )
        )

        addImageURLHook(EXTENSION_CLASS)
        addImageURLSuccessCallbackHook(EXTENSION_CLASS)
        addImageURLErrorCallbackHook(EXTENSION_CLASS)
    }
}
