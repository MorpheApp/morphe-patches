package app.morphe.patches.youtube.misc.debugging

import app.morphe.patches.shared.misc.debugging.enableDebuggingPatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch

@Suppress("unused")
val enableDebuggingPatch = enableDebuggingPatch(
    block = {
        dependsOn(
            sharedExtensionPatch,
            settingsPatch,
        )

        compatibleWith(
            "com.google.android.youtube"(
                "19.43.41",
                "20.14.43",
                "20.21.37",
                "20.31.42",
                "20.46.41",
            )
        )
    },
    executeBlock = {
    },
    hookStringFeatureFlag = true,
    preferenceScreen = PreferenceScreen.MISC,
    additionalDebugPreferences = listOf(SwitchPreference("morphe_debug_protobuffer"))
)
