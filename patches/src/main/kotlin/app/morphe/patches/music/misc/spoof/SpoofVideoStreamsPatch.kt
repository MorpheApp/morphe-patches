package app.morphe.patches.music.misc.spoof

import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.gms.MusicActivityOnCreateFingerprint
import app.morphe.patches.music.misc.settings.PreferenceScreen
import app.morphe.patches.music.misc.settings.settingsPatch
import app.morphe.patches.music.playservice.is_7_16_or_greater
import app.morphe.patches.music.playservice.is_7_33_or_greater
import app.morphe.patches.music.playservice.is_8_11_or_greater
import app.morphe.patches.music.playservice.is_8_15_or_greater
import app.morphe.patches.music.playservice.versionCheckPatch
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.shared.misc.spoof.spoofVideoStreamsPatch

val spoofVideoStreamsPatch = spoofVideoStreamsPatch(
    extensionClassDescriptor = "Lapp/morphe/extension/music/patches/spoof/SpoofVideoStreamsPatch;",
    mainActivityOnCreateFingerprint = MusicActivityOnCreateFingerprint,
    fixMediaFetchHotConfig = { is_7_16_or_greater },
    fixMediaFetchHotConfigAlternative = { is_8_11_or_greater && !is_8_15_or_greater },
    fixParsePlaybackResponseFeatureFlag = { is_7_33_or_greater },

    block = {
        dependsOn(
            sharedExtensionPatch,
            settingsPatch,
            versionCheckPatch,
            userAgentClientSpoofPatch
        )

        compatibleWith(
            "com.google.android.apps.youtube.music"(
                "7.29.52",
                "8.10.52",
                "8.46.57",
            )
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
                )
            )
        )
    }
)
