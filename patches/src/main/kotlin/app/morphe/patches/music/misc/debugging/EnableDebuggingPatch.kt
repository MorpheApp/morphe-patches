package app.morphe.patches.music.misc.debugging

import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.playservice.is_9_37_or_greater
import app.morphe.patches.music.misc.playservice.versionCheckPatch
import app.morphe.patches.music.misc.settings.PreferenceScreen
import app.morphe.patches.music.misc.settings.settingsPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.patches.shared.misc.debugging.enableDebuggingPatch
import app.morphe.patches.shared.misc.settings.preference.ListPreference

val enableDebuggingPatch = enableDebuggingPatch(
    block = {
        dependsOn(
            sharedExtensionPatch,
            settingsPatch,
            versionCheckPatch
        )

        compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)
    },
    hookIndexedBooleanFeatureFlag = { false },
    // String feature flag does not appear to be present with YT Music.
    hookStringFeatureFlag = { false },
    hookLongFeatureFlag = { true },
    hookDoubleFeatureFlag = { true },
    hookHotFeatureFlags = { is_9_37_or_greater },
    preferenceScreen = PreferenceScreen.MISC,
    additionalDebugPreferences = listOf(ListPreference("morphe_debug_litho"))
)
