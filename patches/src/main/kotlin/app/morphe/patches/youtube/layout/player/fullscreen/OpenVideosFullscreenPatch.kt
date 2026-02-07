package app.morphe.patches.youtube.layout.player.fullscreen

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.reddit.utils.compatibility.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.util.setExtensionIsPatchIncluded

@Suppress("unused")
val openVideosFullscreenPatch = bytecodePatch(
    name = "Open videos fullscreen",
    description = "Adds an option to open videos in full screen portrait mode.",
) {
    dependsOn(
        openVideosFullscreenHookPatch,
        settingsPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.PLAYER.addPreferences(
            SwitchPreference("morphe_open_videos_fullscreen_portrait")
        )

        setExtensionIsPatchIncluded(EXTENSION_CLASS_DESCRIPTOR)
    }
}