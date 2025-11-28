package app.morphe.patches.music.misc.settings

import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.patch.resourcePatch
import app.morphe.patches.all.misc.packagename.setOrGetFallbackPackageName
import app.morphe.patches.all.misc.resources.addResources
import app.morphe.patches.all.misc.resources.addResourcesPatch
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.gms.Constants.MUSIC_PACKAGE_NAME
import app.morphe.patches.music.playservice.is_8_40_or_greater
import app.morphe.patches.music.playservice.versionCheckPatch
import app.morphe.patches.shared.boldIconsFeatureFlagFingerprint
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
                    titleKey = "revanced_settings_title",
                    summaryKey = null,
                    intent = newIntent("revanced_settings_intent"),
                ) to "settings_headers"
            ),
            preferences = preferences
        )
    )

    execute {

        // Set the style for the ReVanced settings to follow the style of the music settings,
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
    description = "Adds settings for ReVanced to YouTube Music.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsResourcePatch,
        addResourcesPatch,
        versionCheckPatch
    )

    execute {
        addResources("music", "misc.settings.settingsPatch")
        addResources("shared", "misc.debugging.enableDebuggingPatch")

        // Add an "About" preference to the top.
        if (false) // FIXME: Update this.
        preferences += NonInteractivePreference(
            key = "revanced_settings_music_screen_0_about",
            summaryKey = null,
            tag = "app.morphe.extension.shared.settings.preference.ReVancedAboutPreference",
            selectable = true,
        )

        PreferenceScreen.GENERAL.addPreferences(
            SwitchPreference("revanced_settings_search_history"),
        )

        if (is_8_40_or_greater) {
            PreferenceScreen.GENERAL.addPreferences(
                SwitchPreference("revanced_settings_disable_bold_icons")
            )
        }

        PreferenceScreen.MISC.addPreferences(
            TextPreference(
                key = null,
                titleKey = "revanced_pref_import_export_title",
                summaryKey = "revanced_pref_import_export_summary",
                inputType = InputType.TEXT_MULTI_LINE,
                tag = "app.morphe.extension.shared.settings.preference.ImportExportPreference",
            )
        )

        modifyActivityForSettingsInjection(
            googleApiActivityFingerprint.classDef,
            googleApiActivityFingerprint.method,
            MUSIC_ACTIVITY_HOOK_CLASS_DESCRIPTOR,
            true
        )

        if (is_8_40_or_greater) {
            boldIconsFeatureFlagFingerprint.let {
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
 * Creates an intent to open ReVanced settings.
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
        key = "revanced_settings_music_screen_1_ads",
        summaryKey = null
    )
    val GENERAL = Screen(
        key = "revanced_settings_music_screen_2_general",
        summaryKey = null
    )
    val PLAYER = Screen(
        key = "revanced_settings_music_screen_3_player",
        summaryKey = null
    )
    val MISC = Screen(
        key = "revanced_settings_music_screen_4_misc",
        summaryKey = null
    )

    override fun commit(screen: PreferenceScreenPreference) {
        preferences += screen
    }
}
