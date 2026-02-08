package app.morphe.patches.youtube.layout.captions

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.BasePreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY

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

    compatibleWith(COMPATIBILITY)

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
