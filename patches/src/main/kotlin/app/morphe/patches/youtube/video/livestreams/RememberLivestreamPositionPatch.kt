/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2753
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.video.livestreams

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.shared.misc.settings.preference.noTitleUnsortedPreferenceCategory
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patches.youtube.video.information.onCreateHook
import app.morphe.patches.youtube.video.information.videoInformationPatch
import app.morphe.patches.youtube.video.information.videoTimeHook

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
        PreferenceScreen.VIDEO.addPreferences(
            // Keep the preferences organized together.
            noTitleUnsortedPreferenceCategory(
                SwitchPreference("morphe_remember_livestream_position", summary = true),
                SwitchPreference("morphe_remember_livestream_position_resume_when_live", summary = true)
            )
        )

        // Hook called when a new video starts playing (player controller created).
        onCreateHook(EXTENSION_CLASS, "newVideoStarted")

        // Hook called approximately once per second with the current playback time.
        videoTimeHook(EXTENSION_CLASS, "videoTimeChanged")
    }
}
