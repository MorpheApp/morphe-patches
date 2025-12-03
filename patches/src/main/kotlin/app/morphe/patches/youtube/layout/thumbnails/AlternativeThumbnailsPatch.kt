package app.morphe.patches.youtube.layout.thumbnails

import app.revanced.patcher.patch.bytecodePatch
import app.morphe.patches.all.misc.resources.addResources
import app.morphe.patches.all.misc.resources.addResourcesPatch
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.patches.shared.misc.settings.preference.NonInteractivePreference
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.shared.misc.settings.preference.TextPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.imageurlhook.addImageUrlErrorCallbackHook
import app.morphe.patches.youtube.misc.imageurlhook.addImageUrlHook
import app.morphe.patches.youtube.misc.imageurlhook.addImageUrlSuccessCallbackHook
import app.morphe.patches.youtube.misc.imageurlhook.cronetImageUrlHookPatch
import app.morphe.patches.youtube.misc.navigation.navigationBarHookPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/youtube/patches/AlternativeThumbnailsPatch;"

val alternativeThumbnailsPatch = bytecodePatch(
    name = "Alternative thumbnails",
    description = "Adds options to replace video thumbnails using the DeArrow API or image captures from the video.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        addResourcesPatch,
        navigationBarHookPatch,
        cronetImageUrlHookPatch,
    )

    compatibleWith(
        "com.google.android.youtube"(
            "19.43.41",
            "20.14.43",
            "20.21.37",
            "20.31.42",
            "20.46.41",
        )
    )

    execute {
        addResources("youtube", "layout.thumbnails.alternativeThumbnailsPatch")

        val entries = "morphe_alt_thumbnail_options_entries"
        val values = "morphe_alt_thumbnail_options_entry_values"
        PreferenceScreen.ALTERNATIVE_THUMBNAILS.addPreferences(
            ListPreference(
                key = "morphe_alt_thumbnail_home",
                entriesKey = entries,
                entryValuesKey = values
            ),
            ListPreference(
                key = "morphe_alt_thumbnail_subscription",
                entriesKey = entries,
                entryValuesKey = values
            ),
            ListPreference(
                key = "morphe_alt_thumbnail_library",
                entriesKey = entries,
                entryValuesKey = values
            ),
            ListPreference(
                key = "morphe_alt_thumbnail_player",
                entriesKey = entries,
                entryValuesKey = values
            ),
            ListPreference(
                key = "morphe_alt_thumbnail_search",
                entriesKey = entries,
                entryValuesKey = values
            ),
            NonInteractivePreference(
                "morphe_alt_thumbnail_dearrow_about",
                // Custom about preference with link to the DeArrow website.
                tag = "app.morphe.extension.youtube.settings.preference.AlternativeThumbnailsAboutDeArrowPreference",
                selectable = true,
            ),
            SwitchPreference("morphe_alt_thumbnail_dearrow_connection_toast"),
            TextPreference("morphe_alt_thumbnail_dearrow_api_url"),
            NonInteractivePreference("morphe_alt_thumbnail_stills_about"),
            SwitchPreference("morphe_alt_thumbnail_stills_fast"),
            ListPreference("morphe_alt_thumbnail_stills_time"),
        )

        addImageUrlHook(EXTENSION_CLASS_DESCRIPTOR)
        addImageUrlSuccessCallbackHook(EXTENSION_CLASS_DESCRIPTOR)
        addImageUrlErrorCallbackHook(EXTENSION_CLASS_DESCRIPTOR)
    }
}
