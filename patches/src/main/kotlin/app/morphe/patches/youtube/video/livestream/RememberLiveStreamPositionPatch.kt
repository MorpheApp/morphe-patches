/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2753
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.video.livestream

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
    "Lapp/morphe/extension/youtube/patches/playback/livestream/RememberLiveStreamPositionPatch;"

@Suppress("unused")
val rememberLiveStreamPositionPatch = bytecodePatch(
    name = "Remember live stream playback position",
    description = "Adds an option to remember the playback position of an ongoing live stream " +
        "and resume from there when reopening that live stream.",
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
                SwitchPreference("morphe_remember_live_stream_position", summary = true)
            )
        )

        // Hook called when a new video starts playing (player controller created).
        onCreateHook(EXTENSION_CLASS, "newVideoStarted")

        // Hook called approximately once per second with the current playback time.
        videoTimeHook(EXTENSION_CLASS, "videoTimeChanged")
    }
}
