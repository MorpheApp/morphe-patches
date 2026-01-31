package app.morphe.patches.youtube.layout.captions

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.BasePreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference
import app.morphe.patches.youtube.misc.settings.PreferenceScreen

/**
 * Caption settings.  Used to organize all caption related settings together.
 */
internal val settingsMenuCaptionGroup = mutableSetOf<BasePreference>()

@Suppress("unused")
val captionsPatch = bytecodePatch(
    name = "Captions",
    description = "Adds an option to disable captions from being automatically enabled or to set caption cookies.",
) {
    dependsOn(
        autoCaptionsPatch,
        captionCookiesPatch,
        transcriptPatch,
    )

    compatibleWith(
        "com.google.android.youtube"(
            "20.14.43",
            "20.21.37",
            "20.26.46",
            "20.31.42",
            "20.37.48",
            "20.40.45",
        )
    )

    execute {
        PreferenceScreen.PLAYER.addPreferences(
            PreferenceScreenPreference(
                key = "morphe_captions_screen",
                sorting = PreferenceScreenPreference.Sorting.UNSORTED,
                preferences = settingsMenuCaptionGroup,
            ),
        )
    }
}
