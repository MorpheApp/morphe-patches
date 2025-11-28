package app.morphe.patches.music.misc.debugging

import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.settings.PreferenceScreen
import app.morphe.patches.music.misc.settings.settingsPatch
import app.morphe.patches.shared.misc.debugging.enableDebuggingPatch

@Suppress("unused")
val enableDebuggingPatch = enableDebuggingPatch(
    block = {
        dependsOn(
            sharedExtensionPatch,
            settingsPatch,
        )

        compatibleWith(
            "com.google.android.apps.youtube.music"(
                "7.29.52",
                "8.10.52",
                "8.46.57",
            )
        )
    },
    // String feature flag does not appear to be present with YT Music.
    hookStringFeatureFlag = false,
    preferenceScreen = PreferenceScreen.MISC
)
