package app.morphe.patches.youtube.layout.player.fullscreen

import app.revanced.patcher.patch.bytecodePatch
import app.morphe.patches.all.misc.resources.addResources
import app.morphe.patches.all.misc.resources.addResourcesPatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.util.returnEarly

@Suppress("unused")
val openVideosFullscreenPatch = bytecodePatch(
    name = "Open videos fullscreen",
    description = "Adds an option to open videos in full screen portrait mode.",
) {
    dependsOn(
        openVideosFullscreenHookPatch,
        settingsPatch,
        addResourcesPatch,
    )

    compatibleWith(
        "com.google.android.youtube"(
            "19.43.41",
            "19.43.41",
            "19.47.53",
            "20.14.43",
            "20.21.37",
            "20.31.42",
            "20.46.41",
        )
    )

    execute {
        addResources("youtube", "layout.player.fullscreen.openVideosFullscreen")

        PreferenceScreen.PLAYER.addPreferences(
            SwitchPreference("morphe_open_videos_fullscreen_portrait")
        )

        // Enable the logic for the user Setting to open regular videos fullscreen.
        openVideosFullscreenHookPatchExtensionFingerprint.method.returnEarly(true)
    }
}