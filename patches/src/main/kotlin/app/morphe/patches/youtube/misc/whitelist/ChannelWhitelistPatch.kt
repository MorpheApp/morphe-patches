/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2334
 */

package app.morphe.patches.youtube.misc.whitelist

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.shared.misc.settings.preference.NonInteractivePreference
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patches.youtube.video.speed.settingsMenuVideoSpeedGroup

private const val PREFERENCE_CLASS = "app.morphe.extension.youtube.settings.preference.ChannelWhitelistPreference"

val channelWhitelistResourcePatch = resourcePatch {
    dependsOn(settingsPatch)

    execute {
        PreferenceScreen.ADS.addPreferences(
            NonInteractivePreference(
                key = "morphe_ads_channel_whitelist",
                tag = PREFERENCE_CLASS,
                selectable = true
            )
        )

        settingsMenuVideoSpeedGroup.add(
            NonInteractivePreference(
                key = "morphe_playback_speed_channel_whitelist",
                tag = PREFERENCE_CLASS,
                selectable = true
            )
        )

        PreferenceScreen.FEED.addPreferences(
            SwitchPreference("morphe_ads_channel_whitelist_flyout_menu", summary = true),
            SwitchPreference("morphe_playback_speed_channel_whitelist_flyout_menu", summary = true)
        )
    }
}

@Suppress("unused")
val channelWhitelistPatch = bytecodePatch(
    name = "Channel whitelist",
    description = "Adds options to allow whitelisting specific channels to show ads or override playback speeds."
) {
    dependsOn(channelWhitelistResourcePatch)
    compatibleWith(COMPATIBILITY_YOUTUBE)
    execute {}
}
