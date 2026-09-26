package app.morphe.patches.youtube.layout.livering

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.all.misc.resources.addResourcesPatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patches.youtube.shared.hookVideoIntent
import app.morphe.patches.youtube.shared.openVideoIntentPatch

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/OpenChannelOfLiveAvatarPatch;"

@Suppress("unused")
val openChannelOfLiveAvatarPatch = bytecodePatch(
    name = "Open channel of live avatar",
    description = "Adds an option to prevent a channel's current live video from opening when tapping its avatar."
) {
    compatibleWith(COMPATIBILITY_YOUTUBE)

    dependsOn(
        addResourcesPatch,
        settingsPatch,
        versionCheckPatch,
        openVideoIntentPatch
    )

    execute {
        PreferenceScreen.FEED.addPreferences(
            SwitchPreference("morphe_open_channel_of_live_avatar", summary = true)
        )

        hookVideoIntent(EXTENSION_CLASS, detectVideo = true, detectShorts = true)
    }
}
