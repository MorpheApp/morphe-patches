package app.morphe.patches.shared.misc.settings

//import app.morphe.patches.all.misc.resources.addResource
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.all.misc.resources.addAppResources
import app.morphe.patches.all.misc.resources.addResourcesPatch
import app.morphe.patches.shared.layout.branding.addBrandLicensePatch
import app.morphe.patches.shared.misc.settings.preference.BasePreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceCategory
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference
import app.morphe.util.ResourceGroup
import app.morphe.util.copyResources
import app.morphe.util.getNode
import app.morphe.util.insertFirst
import app.morphe.util.returnEarly
import org.w3c.dom.Node

private var lightThemeColor : String? = null
private var darkThemeColor : String? = null

/**
 * Sets the default theme colors used in various Morphe specific settings menus.
 * By default these colors are white and black, but instead can be set to the
 * same color the target app uses for it's own settings.
 */
fun overrideThemeColors(lightThemeColorString: String?, darkThemeColorString: String) {
    lightThemeColor = lightThemeColorString
    darkThemeColor = darkThemeColorString
}

private val settingsColorPatch = bytecodePatch {
    finalize {
        if (lightThemeColor != null) {
            ThemeLightColorResourceNameFingerprint.method.returnEarly(lightThemeColor!!)
        }
        if (darkThemeColor != null) {
            ThemeDarkColorResourceNameFingerprint.method.returnEarly(darkThemeColor!!)
        }
    }
}

/**
 * A resource patch that adds settings to a settings fragment.
 *
 * @param rootPreferences List of intent preferences and the name of the fragment file to add it to.
 *                        File names that do not exist are ignored and not processed.
 * @param preferences A set of preferences to add to the Morphe fragment.
 */
fun settingsPatch (
    rootPreferences: List<Pair<BasePreference, String>>? = null,
    preferences: Set<BasePreference>,
) = resourcePatch {
    dependsOn(
        addResourcesPatch,
        settingsColorPatch,
        addBrandLicensePatch
    )

    execute {
        addAppResources("shared")

        copyResources(
            "settings",
            ResourceGroup("xml",
                "morphe_prefs.xml",
                "morphe_prefs_icons.xml",
                "morphe_prefs_icons_bold.xml"
            ),
            ResourceGroup("menu",
                "morphe_search_menu.xml"
            ),
            ResourceGroup("drawable",
                // CustomListPreference resources.
                "morphe_ic_dialog_alert.xml",
                // Search resources.
                "morphe_settings_arrow_time.xml",
                "morphe_settings_arrow_time_bold.xml",
                "morphe_settings_custom_checkmark.xml",
                "morphe_settings_custom_checkmark_bold.xml",
                "morphe_settings_search_icon.xml",
                "morphe_settings_search_icon_bold.xml",
                "morphe_settings_search_remove.xml",
                "morphe_settings_search_remove_bold.xml",
                "morphe_settings_toolbar_arrow_left.xml",
                "morphe_settings_toolbar_arrow_left_bold.xml",
            ),
            ResourceGroup("layout",
                "morphe_custom_list_item_checked.xml",
                // Color picker.
                "morphe_color_dot_widget.xml",
                "morphe_color_picker.xml",
                // Search.
                "morphe_preference_search_history_item.xml",
                "morphe_preference_search_history_screen.xml",
                "morphe_preference_search_no_result.xml",
                "morphe_preference_search_result_color.xml",
                "morphe_preference_search_result_group_header.xml",
                "morphe_preference_search_result_list.xml",
                "morphe_preference_search_result_regular.xml",
                "morphe_preference_search_result_switch.xml",
                "morphe_settings_with_toolbar.xml"
            )
        )
    }

    finalize {
        fun Node.addPreference(preference: BasePreference) {
            preference.serialize(ownerDocument) { resource ->
                // FIXME? Not needed anymore?
//                addResource("values", resource)
            }.let { preferenceNode ->
                insertFirst(preferenceNode)
            }
        }

        // Add the root preference to an existing fragment if needed.
        rootPreferences?.let {
            var modified = false

            it.forEach { (intent, fileName) ->
                val preferenceFileName = "res/xml/$fileName.xml"
                if (get(preferenceFileName).exists()) {
                    document(preferenceFileName).use { document ->
                        document.getNode("PreferenceScreen").addPreference(intent)
                    }
                    modified = true
                }
            }

            if (!modified) throw PatchException("No declared preference files exists: $rootPreferences")
        }

        // Add all preferences to the Morphe fragment.
        document("res/xml/morphe_prefs_icons.xml").use { document ->
            val morphePreferenceScreenNode = document.getNode("PreferenceScreen")
            preferences.forEach { morphePreferenceScreenNode.addPreference(it) }
        }

        // Because the icon preferences require declaring a layout resource,
        // there is no easy way to change to the Android default preference layout
        // after the preference is inflated.
        // Using two different preference files is the simplest and most robust solution.
        fun removeIconsAndLayout(preferences: Collection<BasePreference>, removeAllIconsAndLayout: Boolean) {
            preferences.forEach { preference ->
                preference.icon = null
                if (removeAllIconsAndLayout) {
                    preference.iconBold = null
                    preference.layout = null
                }

                if (preference is PreferenceCategory) {
                    removeIconsAndLayout(preference.preferences, removeAllIconsAndLayout)
                } else if (preference is PreferenceScreenPreference) {
                    removeIconsAndLayout(preference.preferences, removeAllIconsAndLayout)
                }
            }
        }

        // Bold icons.
        removeIconsAndLayout(preferences, false)
        document("res/xml/morphe_prefs_icons_bold.xml").use { document ->
            val morphePreferenceScreenNode = document.getNode("PreferenceScreen")
            preferences.forEach { morphePreferenceScreenNode.addPreference(it) }
        }

        removeIconsAndLayout(preferences, true)

        document("res/xml/morphe_prefs.xml").use { document ->
            val morphePreferenceScreenNode = document.getNode("PreferenceScreen")
            preferences.forEach { morphePreferenceScreenNode.addPreference(it) }
        }
    }
}
