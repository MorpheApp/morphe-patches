package app.morphe.patches.youtube.video.quality

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.BasePreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceCategory
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference.Sorting
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE

/**
 * Video quality settings.  Used to organize all speed related settings together.
 */
internal val settingsMenuVideoQualityGroup = mutableSetOf<BasePreference>()

@Suppress("unused")
val videoQualityPatch = bytecodePatch(
    name = "Video quality",
    description = "Adds options to set default video qualities and always use the advanced video quality menu."
) {
    dependsOn(
        rememberVideoQualityPatch,
        advancedVideoQualityMenuPatch,
        hidePremiumVideoQualityPatch,
        prioritizeVideoQualityPatch,
        videoQualityDialogButtonPatch
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.VIDEO.addPreferences(
            // Keep the preferences organized together.
            PreferenceCategory(
                key = "morphe_01_video_key", // Dummy key to force the quality preferences first.
                titleKey = null,
                sorting = Sorting.UNSORTED,
                tag = "app.morphe.extension.shared.settings.preference.NoTitlePreferenceCategory",
                preferences = settingsMenuVideoQualityGroup
            )
        )
    }
}
