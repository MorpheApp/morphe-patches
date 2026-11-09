package app.morphe.patches.youtube.misc.debugging

import app.morphe.patches.shared.misc.debugging.enableDebuggingPatch
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playservice.is_20_40_or_greater
import app.morphe.patches.youtube.misc.playservice.is_20_41_or_greater
import app.morphe.patches.youtube.misc.playservice.is_21_35_or_greater
import app.morphe.patches.youtube.misc.playservice.is_21_42_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE

@Suppress("unused")
val enableDebuggingPatch = enableDebuggingPatch(
    block = {
        dependsOn(
            sharedExtensionPatch,
            settingsPatch,
            versionCheckPatch
        )

        compatibleWith(COMPATIBILITY_YOUTUBE)
    },
    executeBlock = { },
    hookIndexedBooleanFeatureFlag = { is_21_42_or_greater },
    hookStringFeatureFlag = { true },
    // 20.40 has changes not worth supporting.
    hookLongFeatureFlag = { !is_20_40_or_greater || is_20_41_or_greater},
    hookDoubleFeatureFlag = { !is_20_40_or_greater || is_20_41_or_greater},
    hookHotFeatureFlags = { is_21_35_or_greater },
    preferenceScreen = PreferenceScreen.MISC,
    additionalDebugPreferences = listOf(ListPreference("morphe_debug_litho"))
)
