package app.morphe.patches.music.misc.settings

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.all.misc.fix.openurllinks.removeLinkVerification
import app.morphe.patches.all.misc.packagename.setOrGetFallbackPackageName
import app.morphe.patches.all.misc.resources.addAppResources
import app.morphe.patches.all.misc.resources.addResourcesPatch
import app.morphe.patches.all.misc.resources.localesYouTube
import app.morphe.patches.all.misc.resources.resourceMappingPatch
import app.morphe.patches.all.misc.resources.setAddResourceLocale
import app.morphe.patches.all.misc.updates.checkPatcherUpToDatePatch
import app.morphe.patches.music.misc.extension.hooks.youTubeMusicApplicationInitOnCreateHook
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.gms.Constants.MUSIC_PACKAGE_NAME
import app.morphe.patches.music.misc.playservice.is_8_40_or_greater
import app.morphe.patches.music.misc.playservice.versionCheckPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.patches.shared.BoldIconsFeatureFlagFingerprint
import app.morphe.patches.shared.GoogleApiActivityOnCreateFingerprint
import app.morphe.patches.shared.misc.checks.experimentalAppNoticePatch
import app.morphe.patches.shared.misc.initialization.initializationPatch
import app.morphe.patches.shared.misc.settings.MORPHE_SETTINGS_INTENT
import app.morphe.patches.shared.misc.settings.preference.BasePreference
import app.morphe.patches.shared.misc.settings.preference.BasePreferenceScreen
import app.morphe.patches.shared.misc.settings.preference.InputType
import app.morphe.patches.shared.misc.settings.preference.IntentPreference
import app.morphe.patches.shared.misc.settings.preference.NonInteractivePreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference.Sorting
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.shared.misc.settings.preference.TextPreference
import app.morphe.patches.shared.misc.settings.settingsPatch
import app.morphe.patches.youtube.misc.settings.modifyActivityForSettingsInjection
import app.morphe.util.ResourceGroup
import app.morphe.util.copyResources
import app.morphe.util.copyXmlNode
import app.morphe.util.inputStreamFromBundledResource
import app.morphe.util.insertLiteralOverride

private const val MUSIC_ACTIVITY_HOOK_CLASS = "Lapp/morphe/extension/music/settings/MusicActivityHook;"

private val preferences = mutableSetOf<BasePreference>()

private val settingsResourcePatch = resourcePatch {
    dependsOn(
        resourceMappingPatch,
        settingsPatch(
            rootPreferences = listOf(
                IntentPreference(
                    titleKey = "morphe_settings_title",
                    summaryKey = null,
                    intent = newIntent(MORPHE_SETTINGS_INTENT),
                ) to "settings_headers"
            ),
            preferences = preferences
        )
    )

    execute {
        copyResources(
            "settings",
            ResourceGroup("drawable",
                "morphe_settings_screen_00_about.xml",
                "morphe_settings_screen_00_about_bold.xml",
                "morphe_settings_screen_01_ads.xml",
                "morphe_settings_screen_01_ads_bold.xml",
                "morphe_settings_screen_04_general.xml",
                "morphe_settings_screen_04_general_bold.xml",
                "morphe_settings_screen_05_player.xml",
                "morphe_settings_screen_05_player_bold.xml",
                "morphe_settings_screen_10_sponsorblock.xml",
                "morphe_settings_screen_10_sponsorblock_bold.xml",
                "morphe_settings_screen_11_misc.xml",
                "morphe_settings_screen_11_misc_bold.xml",
                "morphe_settings_screen_13_scrobbling.xml",
                "morphe_settings_screen_13_scrobbling_bold.xml"
            ),
            ResourceGroup("layout",
                "morphe_preference_with_icon.xml",
                "morphe_color_dot_widget.xml"
            )
        )

        // Set the style for the Morphe settings to follow the style of the music settings,
        // namely: action bar height, menu item padding and remove horizontal dividers.
        val targetResource = "values/styles.xml"
        inputStreamFromBundledResource(
            "settings/music",
            targetResource
        )!!.let { inputStream ->
            "resources".copyXmlNode(
                document(inputStream),
                document("res/$targetResource")
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
    description = "Adds settings for Morphe to YouTube Music."
) {
    dependsOn(
        checkPatcherUpToDatePatch,
        sharedExtensionPatch,
        settingsResourcePatch,
        addResourcesPatch,
        versionCheckPatch,
        removeLinkVerification,
        experimentalAppNoticePatch(
            mainActivityFingerprint = youTubeMusicApplicationInitOnCreateHook.fingerprint,
            recommendedAppVersion = COMPATIBILITY_YOUTUBE_MUSIC.targets.first { !it.isExperimental }.version!!
        ),
        initializationPatch(
            extensionPatch = sharedExtensionPatch
        )
    )

    execute {
        setAddResourceLocale(localesYouTube)
        addAppResources("shared-youtube")
        addAppResources("music")

        // Add an "About" preference to the top.
        preferences += NonInteractivePreference(
            key = "morphe_settings_music_screen_0_about",
            summaryKey = null,
            icon = "@drawable/morphe_settings_screen_00_about",
            iconBold = "@drawable/morphe_settings_screen_00_about_bold",
            layout = "@layout/morphe_preference_with_icon",
            tag = "app.morphe.extension.shared.settings.preference.about.MorpheAboutPreference",
            selectable = true
        )

        PreferenceScreen.GENERAL.addPreferences(
            SwitchPreference("morphe_settings_search_history"),
            SwitchPreference("morphe_show_menu_icons")
        )

        PreferenceScreen.MISC.addPreferences(
            TextPreference(
                key = null,
                titleKey = "morphe_pref_import_export_title",
                summaryKey = "morphe_pref_import_export_summary",
                inputType = InputType.TEXT_MULTI_LINE,
                tag = "app.morphe.extension.shared.settings.preference.ImportExportPreference"
            )
        )

        modifyActivityForSettingsInjection(
            GoogleApiActivityOnCreateFingerprint,
            MUSIC_ACTIVITY_HOOK_CLASS,
            true
        )

        // TODO: Implement a 'Spoof app version' patch for YouTube Music.
        if (is_8_40_or_greater) {
            BoldIconsFeatureFlagFingerprint.let {
                it.method.insertLiteralOverride(
                    it.instructionMatches.first().index,
                    "$MUSIC_ACTIVITY_HOOK_CLASS->useBoldIcons(Z)Z"
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
        summaryKey = null,
        icon = "@drawable/morphe_settings_screen_01_ads",
        iconBold = "@drawable/morphe_settings_screen_01_ads_bold",
        layout = "@layout/morphe_preference_with_icon"
    )
    val GENERAL = Screen(
        key = "morphe_settings_music_screen_2_general",
        summaryKey = null,
        icon = "@drawable/morphe_settings_screen_04_general",
        iconBold = "@drawable/morphe_settings_screen_04_general_bold",
        layout = "@layout/morphe_preference_with_icon"
    )
    val PLAYER = Screen(
        key = "morphe_settings_music_screen_3_player",
        summaryKey = null,
        icon = "@drawable/morphe_settings_screen_05_player",
        iconBold = "@drawable/morphe_settings_screen_05_player_bold",
        layout = "@layout/morphe_preference_with_icon"
    )
    val SCROBBLING = Screen(
        key = "morphe_settings_music_screen_4_scrobbling",
        summaryKey = null,
        icon = "@drawable/morphe_settings_screen_13_scrobbling",
        iconBold = "@drawable/morphe_settings_screen_13_scrobbling_bold",
        layout = "@layout/morphe_preference_with_icon",
        sorting = Sorting.UNSORTED
    )
    val SPONSORBLOCK = Screen(
        key = "morphe_settings_music_screen_5_sponsorblock",
        summaryKey = null,
        icon = "@drawable/morphe_settings_screen_10_sponsorblock",
        iconBold = "@drawable/morphe_settings_screen_10_sponsorblock_bold",
        layout = "@layout/morphe_preference_with_icon",
        sorting = Sorting.UNSORTED
    )
    val MISC = Screen(
        key = "morphe_settings_music_screen_6_misc",
        summaryKey = null,
        icon = "@drawable/morphe_settings_screen_11_misc",
        iconBold = "@drawable/morphe_settings_screen_11_misc_bold",
        layout = "@layout/morphe_preference_with_icon"
    )

    override fun commit(screen: PreferenceScreenPreference) {
        preferences += screen
    }
}
