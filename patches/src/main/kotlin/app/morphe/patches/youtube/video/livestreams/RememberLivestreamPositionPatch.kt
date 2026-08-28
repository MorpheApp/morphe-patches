/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.video.livestreams

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.BasePreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceCategory
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference.Sorting
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patches.youtube.video.information.onCreateHook
import app.morphe.patches.youtube.video.information.videoInformationPatch
import app.morphe.patches.youtube.video.information.videoTimeHook
import app.morphe.util.setExtensionIsPatchIncluded

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/playback/livestreams/RememberLivestreamPositionPatch;"

@Suppress("unused")
val rememberLivestreamPositionPatch = bytecodePatch(
    name = "Remember livestream playback position",
    description = "Adds an option to remember the playback position of ongoing livestreams " +
        "and resume from there when reopening a livestream.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        videoInformationPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        setExtensionIsPatchIncluded(EXTENSION_CLASS)

        PreferenceScreen.VIDEO.addPreferences(
            // Keep the preferences organized together.
            PreferenceCategory(
                key = "morphe_02_video_livestream_key", // Dummy key to sort after the video quality settings.
                titleKey = null,
                sorting = Sorting.UNSORTED,
                tag = "app.morphe.extension.shared.settings.preference.NoTitlePreferenceCategory",
                preferences = mutableSetOf<BasePreference>(
                    SwitchPreference("morphe_remember_livestream_position", summary = true),
                    SwitchPreference("morphe_remember_livestream_position_resume_when_live", summary = true)
                )
            )
        )

        // Hook called when a new video starts playing (player controller created).
        onCreateHook(EXTENSION_CLASS, "newVideoStarted")

        // Hook called approximately once per second with the current playback time.
        videoTimeHook(EXTENSION_CLASS, "videoTimeChanged")
    }
}
