package app.morphe.patches.music.misc.backgroundplayback

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.settings.settingsPatch
import app.morphe.util.returnEarly

val backgroundPlaybackPatch = bytecodePatch(
    name = "Remove background playback restrictions",
    description = "Removes restrictions on background playback, including playing kids videos in the background.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch
    )

    compatibleWith(
        "com.google.android.apps.youtube.music"(
            "7.29.52",
            "8.10.52",
            "8.37.56",
        )
    )

    execute {
        KidsBackgroundPlaybackPolicyControllerFingerprint.method.returnEarly()

        BackgroundPlaybackDisableFingerprint.method.returnEarly(true)
    }
}
