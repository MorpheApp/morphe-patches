/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3982
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.video.speed.shorts

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.layout.flyout.flyoutPatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.video.information.videoInformationPatch
import app.morphe.patches.youtube.video.speed.custom.customPlaybackSpeedPatch
import app.morphe.patches.youtube.video.speed.settingsMenuShortsSpeedGroup
import app.morphe.util.setExtensionIsPatchIncluded

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/playback/speed/ShortsPlaybackSpeedFlyoutPatch;"

internal val shortsPlaybackSpeedFlyoutPatch = bytecodePatch(
    description = "Adds a playback speed item to the flyout menu of the Shorts player.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        flyoutPatch,
        videoInformationPatch,
        customPlaybackSpeedPatch,
    )

    execute {
        settingsMenuShortsSpeedGroup.add(
            SwitchPreference("morphe_shorts_playback_speed_flyout_button", summary = true)
        )

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}
