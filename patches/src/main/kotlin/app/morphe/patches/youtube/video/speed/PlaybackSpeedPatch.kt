package app.morphe.patches.youtube.video.speed

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.BasePreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceCategory
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference.Sorting
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.video.speed.button.playbackSpeedButtonPatch
import app.morphe.patches.youtube.video.speed.custom.customPlaybackSpeedPatch
import app.morphe.patches.youtube.video.speed.remember.rememberPlaybackSpeedPatch

/**
 * Speed menu settings.  Used to organize all speed related settings together.
 */
internal val settingsMenuVideoSpeedGroup = mutableSetOf<BasePreference>()

@Suppress("unused")
val playbackSpeedPatch = bytecodePatch(
    name = "Playback speed",
    description = "Adds options to customize available playback speeds, set a default playback speed, " +
        "and show a speed dialog button in the video player.",
) {
    dependsOn(
        customPlaybackSpeedPatch,
        rememberPlaybackSpeedPatch,
        playbackSpeedButtonPatch,
    )

    compatibleWith(
        "com.google.android.youtube"(
            "20.14.43",
            "20.21.37",
            "20.31.42",
            "20.46.41",
        )
    )

    execute {
        PreferenceScreen.VIDEO.addPreferences(
            PreferenceCategory(
                key = "morphe_zz_video_key", // Dummy key to force the speed settings last.
                titleKey = null,
                sorting = Sorting.UNSORTED,
                tag = "app.morphe.extension.shared.settings.preference.NoTitlePreferenceCategory",
                preferences = settingsMenuVideoSpeedGroup
            )
        )
    }
}
