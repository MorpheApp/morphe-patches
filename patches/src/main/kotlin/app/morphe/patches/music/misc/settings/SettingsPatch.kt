package app.morphe.patches.music.misc.settings

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.all.misc.packagename.setOrGetFallbackPackageName
import app.morphe.patches.all.misc.resources.addAppResources
import app.morphe.patches.all.misc.resources.addResourcesPatch
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.gms.Constants.MUSIC_PACKAGE_NAME
import app.morphe.patches.music.playservice.is_8_40_or_greater
import app.morphe.patches.music.playservice.versionCheckPatch
import app.morphe.patches.shared.BoldIconsFeatureFlagFingerprint
import app.morphe.patches.shared.misc.mapping.resourceMappingPatch
import app.morphe.patches.shared.misc.settings.preference.BasePreference
import app.morphe.patches.shared.misc.settings.preference.BasePreferenceScreen
import app.morphe.patches.shared.misc.settings.preference.InputType
import app.morphe.patches.shared.misc.settings.preference.IntentPreference
import app.morphe.patches.shared.misc.settings.preference.NonInteractivePreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.shared.misc.settings.preference.TextPreference
import app.morphe.patches.shared.misc.settings.settingsPatch
import app.morphe.patches.youtube.misc.settings.modifyActivityForSettingsInjection
import app.morphe.util.copyXmlNode
import app.morphe.util.inputStreamFromBundledResource
import app.morphe.util.insertLiteralOverride

private const val MUSIC_ACTIVITY_HOOK_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/music/settings/MusicActivityHook;"

private val preferences = mutableSetOf<BasePreference>()

private val settingsResourcePatch = resourcePatch {
    dependsOn(
        resourceMappingPatch,
        settingsPatch(
            rootPreferences = listOf(
                IntentPreference(
                    titleKey = "morphe_settings_title",
                    summaryKey = null,
                    intent = newIntent("morphe_settings_intent"),
                ) to "settings_headers"
            ),
            preferences = preferences
        )
    )

    execute {
        // Set the style for the Morphe settings to follow the style of the music settings,
        // namely: action bar height, menu item padding and remove horizontal dividers.
        val targetResource = "values/styles.xml"
        inputStreamFromBundledResource(
            "settings/music",
            targetResource,
        )!!.let { inputStream ->
            "resources".copyXmlNode(
                document(inputStream),
                document("res/$targetResource"),
            ).close()
        }

        // Remove horizontal dividers from the music settings.
        val styleFile = get("res/values/styles.xml")
        styleFile.writeText(
            styleFile.readText()
                .replace(
                    "allowDividerAbove\">true",
                    "allowDividerAbove\">false"
                ).replace(
                    "allowDividerBelow\">true",
                    "allowDividerBelow\">false"
                )
        )
    }
}

val settingsPatch = bytecodePatch(
    description = "Adds settings for Morphe to YouTube Music.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsResourcePatch,
        addResourcesPatch,
        versionCheckPatch
    )

    execute {
        addAppResources("shared-youtube")
        addAppResources("music")

        // Add an "About" preference to the top.
        preferences += NonInteractivePreference(
            key = "morphe_settings_music_screen_0_about",
            summaryKey = null,
            tag = "app.morphe.extension.shared.settings.preference.MorpheAboutPreference",
            selectable = true,
        )

        PreferenceScreen.GENERAL.addPreferences(
            SwitchPreference("morphe_settings_search_history"),
        )

        if (is_8_40_or_greater) {
            PreferenceScreen.GENERAL.addPreferences(
                SwitchPreference("morphe_settings_disable_bold_icons")
            )
        }

        PreferenceScreen.MISC.addPreferences(
            TextPreference(
                key = null,
                titleKey = "morphe_pref_import_export_title",
                summaryKey = "morphe_pref_import_export_summary",
                inputType = InputType.TEXT_MULTI_LINE,
                tag = "app.morphe.extension.shared.settings.preference.ImportExportPreference",
            )
        )

        modifyActivityForSettingsInjection(
            GoogleApiActivityFingerprint.classDef,
            GoogleApiActivityFingerprint.method,
            MUSIC_ACTIVITY_HOOK_CLASS_DESCRIPTOR,
            true
        )

        if (is_8_40_or_greater) {
            BoldIconsFeatureFlagFingerprint.let {
                it.method.insertLiteralOverride(
                    it.instructionMatches.first().index,
                    "$MUSIC_ACTIVITY_HOOK_CLASS_DESCRIPTOR->useBoldIcons(Z)Z"
                )
            }
        }
    }

    finalize {
        PreferenceScreen.close()
    }
}

/**
 * Creates an intent to open Morphe settings.
 */
fun newIntent(settingsName: String) = IntentPreference.Intent(
    data = settingsName,
    targetClass = "com.google.android.gms.common.api.GoogleApiActivity"
) {
    // The package name change has to be reflected in the intent.
    setOrGetFallbackPackageName(MUSIC_PACKAGE_NAME)
}

object PreferenceScreen : BasePreferenceScreen() {
    val ADS = Screen(
        key = "morphe_settings_music_screen_1_ads",
        summaryKey = null
    )
    val GENERAL = Screen(
        key = "morphe_settings_music_screen_2_general",
        summaryKey = null
    )
    val PLAYER = Screen(
        key = "morphe_settings_music_screen_3_player",
        summaryKey = null
    )
    val MISC = Screen(
        key = "morphe_settings_music_screen_4_misc",
        summaryKey = null
    )

    override fun commit(screen: PreferenceScreenPreference) {
        preferences += screen
    }
}
