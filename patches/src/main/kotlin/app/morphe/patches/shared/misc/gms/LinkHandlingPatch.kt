package app.morphe.patches.shared.misc.gms

import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.shared.misc.settings.preference.BasePreferenceScreen
import app.morphe.patches.shared.misc.settings.preference.NonInteractivePreference

/**
 * Adds a preference to guide the user through re-assigning app links
 * from the original package to the patched package.
 *
 * @param fromPackageName The original unpatched app package name.
 * @param screen The preference screen to add the preference to.
 */
internal fun linkHandlingPatch(
    fromPackageName: String,
    screen: BasePreferenceScreen.Screen,
) = resourcePatch {
    finalize {
        screen.addPreferences(
            NonInteractivePreference(
                key = "morphe_link_handling:$fromPackageName",
                titleKey = "morphe_link_handling_title",
                summaryKey = "morphe_link_handling_summary",
                tag = "app.morphe.extension.shared.settings.preference.LinkHandlingPreference",
                selectable = true,
            )
        )
    }
}
