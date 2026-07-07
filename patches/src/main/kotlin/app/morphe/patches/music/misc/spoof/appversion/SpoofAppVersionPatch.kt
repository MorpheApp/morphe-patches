package app.morphe.patches.music.misc.spoof.appversion

import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.settings.PreferenceScreen
import app.morphe.patches.music.misc.settings.settingsPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.patches.shared.misc.spoof.appversion.baseSpoofAppVersionPatch

@Suppress("unused")
val spoofAppVersionPatch = baseSpoofAppVersionPatch(
    defaultTargetString = { "7.29.52" },
    preferenceScreen = PreferenceScreen.GENERAL,
    listPreference = {
        ListPreference(
            key = "morphe_spoof_app_version_target",
            entriesKey = "morphe_music_spoof_app_version_target_entries",
            entryValuesKey = "morphe_music_spoof_app_version_target_entry_values"
        )
    },
    block = {
        dependsOn(
            sharedExtensionPatch,
            settingsPatch
        )

        compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)
    }
)
