package app.morphe.patches.youtube.misc.spoof

import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.patches.shared.misc.settings.preference.NonInteractivePreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.shared.misc.spoof.spoofVideoStreamsPatch
import app.morphe.patches.youtube.misc.playservice.is_19_34_or_greater
import app.morphe.patches.youtube.misc.playservice.is_20_03_or_greater
import app.morphe.patches.youtube.misc.playservice.is_20_10_or_greater
import app.morphe.patches.youtube.misc.playservice.is_20_14_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.MainActivityOnCreateFingerprint

val spoofVideoStreamsPatch = spoofVideoStreamsPatch(
    extensionClassDescriptor = "Lapp/morphe/extension/youtube/patches/spoof/SpoofVideoStreamsPatch;",
    mainActivityOnCreateFingerprint = MainActivityOnCreateFingerprint,
    fixMediaFetchHotConfig = {
        is_19_34_or_greater
    },
    fixMediaFetchHotConfigAlternative = {
        // In 20.14 the flag was merged with 20.03 start playback flag.
        is_20_10_or_greater && !is_20_14_or_greater
    },
    fixParsePlaybackResponseFeatureFlag = {
        is_20_03_or_greater
    },

    block = {
        compatibleWith(
            "com.google.android.youtube"(
                "20.14.43",
                "20.21.37",
                "20.31.42",
                "20.46.41",
            )
        )

        dependsOn(
            userAgentClientSpoofPatch,
            settingsPatch,
            versionCheckPatch
        )
    },

    executeBlock = {

        PreferenceScreen.MISC.addPreferences(
            PreferenceScreenPreference(
                key = "morphe_spoof_video_streams_screen",
                sorting = PreferenceScreenPreference.Sorting.UNSORTED,
                preferences = setOf(
                    SwitchPreference("morphe_spoof_video_streams"),
                    ListPreference("morphe_spoof_video_streams_client_type"),
                    NonInteractivePreference(
                        // Requires a key and title but the actual text is chosen at runtime.
                        key = "morphe_spoof_video_streams_about",
                        summaryKey = null,
                        tag = "app.morphe.extension.youtube.settings.preference.SpoofStreamingDataSideEffectsPreference"
                    ),
                    SwitchPreference("morphe_spoof_video_streams_av1"),
                    SwitchPreference("morphe_spoof_streaming_data_stats_for_nerds"),
                )
            )
        )
    }
)
