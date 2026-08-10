package app.morphe.patches.shared.misc.settings

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.ResourcePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.all.misc.resources.addAppResources
import app.morphe.patches.all.misc.resources.addResourcesPatch
import app.morphe.patches.shared.layout.branding.addLicensePatch
import app.morphe.patches.shared.misc.settings.preference.BasePreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceCategory
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference
import app.morphe.util.ResourceGroup
import app.morphe.util.childElementsSequence
import app.morphe.util.copyResources
import app.morphe.util.forEachChildElement
import app.morphe.util.getNode
import app.morphe.util.insertFirst
import app.morphe.util.returnEarly
import org.w3c.dom.Node

const val MORPHE_SETTINGS_INTENT = "morphe_settings_intent"

private var lightThemeColor : String? = null
private var darkThemeColor : String? = null

/**
 * Sets the default theme colors used in various Morphe specific settings menus.
 * By default, these colors are white and black, but instead can be set to the
 * same color the target app uses for its own settings.
 */
fun overrideThemeColors(lightThemeColorString: String?, darkThemeColorString: String) {
    lightThemeColor = lightThemeColorString
    darkThemeColor = darkThemeColorString
}

private val settingsColorPatch = bytecodePatch {
    finalize {
        lightThemeColor?.let { ThemeLightColorResourceNameFingerprint.method.returnEarly(it) }
        darkThemeColor?.let { ThemeDarkColorResourceNameFingerprint.method.returnEarly(it) }
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
        addLicensePatch
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
                "morphe_icon_list_item.xml",
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
            preference.serialize(ownerDocument) { _ ->
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

        // Add-on bundles are loaded in their own class loader and cannot use the preference
        // classes of this bundle, so they declare their preferences as plain XML instead.
        // This runs last, because the screens an add-on adds to are created above.
        mergeAddOnPreferences()
    }
}

/**
 * Path of the file an add-on patch bundle declares its preferences in.
 * The file is merged into the Morphe preference files and then removed.
 *
 * The file is intentionally not a resource file, so a leftover declaration
 * of an add-on used without this patch cannot break resource compilation.
 */
const val ADD_ON_PREFERENCES_FILE_PATH = "morphe_addon_prefs.xml"

/**
 * Preferences an add-on patch bundle declared, and where to add them.
 *
 * @param screenKey Key of the screen to add the preferences to,
 *                  or null to add them to the root screen.
 * @param afterKey Key of the preference to add the preferences after.
 *                 Takes precedence over [screenKey].
 */
private class AddOnPreferences(
    val screenKey: String?,
    val afterKey: String?,
    val preferences: List<Node>,
)

/**
 * Merges the preferences declared by add-on patch bundles into the Morphe preference files.
 *
 * The declaration file is a list of screens, and each screen declares where its preferences go.
 * Either next to an existing preference, which is how an add-on adds itself next to the built-in
 * feature it extends, or into a screen by key:
 *
 * ```xml
 * <morphe-add-on-preferences>
 *     <screen after="morphe_vot_screen">
 *         <PreferenceScreen android:key="..." android:title="@string/..." />
 *     </screen>
 *     <screen key="morphe_settings_screen_12_video">
 *         <SwitchPreference android:key="..." android:title="@string/..." />
 *     </screen>
 * </morphe-add-on-preferences>
 * ```
 *
 * Both attributes are optional, and preferences of a screen that declares neither, or declares
 * keys that do not exist, are added to the root screen. Screens that sort their preferences do so
 * by key, so an add-on that wants to stay next to a preference must also use a key that sorts next
 * to it. Icons are not supported, since the same nodes are used for all preference file variants.
 */
context(context: ResourcePatchContext)
private fun mergeAddOnPreferences() {
    val declarationFile = context[ADD_ON_PREFERENCES_FILE_PATH]
    if (!declarationFile.exists()) return

    val addOnPreferences = mutableListOf<AddOnPreferences>()

    context.document(ADD_ON_PREFERENCES_FILE_PATH).use { declarations ->
        val declarationsNode = declarations.getNode("morphe-add-on-preferences")
            ?: throw PatchException("Invalid add-on preference declaration: $ADD_ON_PREFERENCES_FILE_PATH")

        declarationsNode.forEachChildElement { screen ->
            addOnPreferences += AddOnPreferences(
                screenKey = screen.getAttribute("key").takeIf { it.isNotEmpty() },
                afterKey = screen.getAttribute("after").takeIf { it.isNotEmpty() },
                preferences = screen.childElementsSequence().toList(),
            )
        }
    }

    if (addOnPreferences.isEmpty()) {
        declarationFile.delete()
        return
    }

    arrayOf(
        "res/xml/morphe_prefs.xml",
        "res/xml/morphe_prefs_icons.xml",
        "res/xml/morphe_prefs_icons_bold.xml"
    ).forEach { preferenceFilePath ->
        if (!context[preferenceFilePath].exists()) return@forEach

        context.document(preferenceFilePath).use { document ->
            val rootNode = document.getNode("PreferenceScreen")

            addOnPreferences.forEach { addOn ->
                val siblingNode = addOn.afterKey?.let { rootNode.findPreferenceByKey(it) }

                if (siblingNode != null) {
                    var insertAfterNode: Node = siblingNode

                    addOn.preferences.forEach { preference ->
                        val preferenceNode = document.importNode(preference, true)
                        siblingNode.parentNode.insertBefore(
                            preferenceNode,
                            insertAfterNode.nextSibling // Null appends to the end.
                        )
                        insertAfterNode = preferenceNode
                    }
                } else {
                    val screenNode = addOn.screenKey?.let { rootNode.findPreferenceByKey(it) }
                        ?: rootNode

                    addOn.preferences.forEach { preference ->
                        screenNode.appendChild(document.importNode(preference, true))
                    }
                }
            }
        }
    }

    // The declaration file itself is not part of the app.
    declarationFile.delete()
}

/**
 * @return The preference node with the given key,
 *         or null if no preference with that key exists.
 */
private fun Node.findPreferenceByKey(key: String): Node? {
    childElementsSequence().forEach { element ->
        val elementKey = element.getAttribute("android:key")
        // Screens and categories that are sorted have the sort type appended to their key.
        if (elementKey == key || elementKey.startsWith("${key}_sort_by")) {
            return element
        }

        element.findPreferenceByKey(key)?.let { return it }
    }

    return null
}
