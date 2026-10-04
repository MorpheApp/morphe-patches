package app.morphe.patches.youtube.layout.dearrow

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.patches.shared.misc.settings.preference.NonInteractivePreference
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
        // Titles are replaced by the same hooks that restore the original titles.
        restoreOriginalTitlesPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        val entries = "morphe_dearrow_thumbnail_options_entries"
        val values = "morphe_dearrow_thumbnail_options_entry_values"
        PreferenceScreen.DEARROW.addPreferences(
            SwitchPreference("morphe_dearrow_titles", summary = true),
            ListPreference(
                key = "morphe_dearrow_thumbnail_home",
                entriesKey = entries,
                entryValuesKey = values
            ),
            ListPreference(
                key = "morphe_dearrow_thumbnail_subscription",
                entriesKey = entries,
                entryValuesKey = values
            ),
            ListPreference(
                key = "morphe_dearrow_thumbnail_library",
                entriesKey = entries,
                entryValuesKey = values
            ),
            ListPreference(
                key = "morphe_dearrow_thumbnail_player",
                entriesKey = entries,
                entryValuesKey = values
            ),
            ListPreference(
                key = "morphe_dearrow_thumbnail_search",
                entriesKey = entries,
                entryValuesKey = values
            ),
            NonInteractivePreference(
                "morphe_dearrow_about",
                // Custom about preference with link to the DeArrow website.
                tag = "app.morphe.extension.youtube.settings.preference.DeArrowAboutPreference",
                selectable = true,
            ),
            SwitchPreference("morphe_dearrow_connection_toast", summary = true),
            TextPreference("morphe_dearrow_api_url"),
            NonInteractivePreference("morphe_dearrow_thumbnail_stills_about"),
            SwitchPreference("morphe_dearrow_thumbnail_stills_fast", summary = true),
            ListPreference("morphe_dearrow_thumbnail_stills_time"),
        )

        addImageURLHook(EXTENSION_CLASS)
        addImageURLSuccessCallbackHook(EXTENSION_CLASS)
        addImageURLErrorCallbackHook(EXTENSION_CLASS)
    }
}
