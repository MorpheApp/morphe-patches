/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.misc.settings

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.patch
import app.morphe.patcher.patch.readXml
import app.morphe.patcher.patch.resourceXml
import app.morphe.patcher.util.cloneParameters
import app.morphe.patcher.util.getFreeRegisterProvider
import app.morphe.patcher.util.p0Register
import app.morphe.patches.all.misc.clone.setOrGetFallbackPackageName
import app.morphe.patches.all.misc.crashlog.crashLogPatch
import app.morphe.patches.all.misc.fix.openurllinks.removeLinkVerification
import app.morphe.patches.all.misc.resources.addAppResources
import app.morphe.patches.all.misc.resources.addResourcesPatch
import app.morphe.patches.all.misc.resources.localesYouTube
import app.morphe.patches.all.misc.resources.setAddResourceLocale
import app.morphe.patches.music.misc.extension.hooks.youTubeMusicApplicationInitOnCreateHook
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.gms.Constants.MUSIC_PACKAGE_NAME
import app.morphe.patches.music.misc.playservice.versionCheckPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.patches.music.shared.MusicActivityOnCreateFingerprint
import app.morphe.patches.shared.BoldIconsFeatureFlagFingerprint
import app.morphe.patches.shared.GoogleApiActivityOnCreateFingerprint
import app.morphe.patches.shared.misc.announcements.patchesUpdateNoticePatch
import app.morphe.patches.shared.misc.checks.experimentalAppNoticePatch
import app.morphe.patches.shared.misc.initialization.initializationPatch
import app.morphe.patches.shared.misc.settings.MORPHE_SETTINGS_INTENT
import app.morphe.patches.shared.misc.settings.SETTINGS_NAME_PREFERENCE_KEY
import app.morphe.patches.shared.misc.settings.customSettingsNameInstructions
import app.morphe.patches.shared.misc.settings.customSettingsNamePreference
import app.morphe.patches.shared.misc.settings.preference.BasePreference
import app.morphe.patches.shared.misc.settings.preference.BasePreferenceScreen
import app.morphe.patches.shared.misc.settings.preference.InputType
import app.morphe.patches.shared.misc.settings.preference.IntentPreference
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.patches.shared.misc.settings.preference.NonInteractivePreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference.Sorting
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.shared.misc.settings.preference.TextPreference
import app.morphe.patches.shared.misc.settings.settingsPatch
import app.morphe.patches.youtube.misc.settings.modifyActivityForSettingsInjection
import app.morphe.util.ResourceGroup
import app.morphe.util.copyResources
import app.morphe.util.inputStreamFromBundledResource
import app.morphe.util.insertLiteralOverride

private const val MUSIC_ACTIVITY_HOOK_CLASS = "Lapp/morphe/extension/music/settings/MusicActivityHook;"

private val preferences = mutableSetOf<BasePreference>()

private val settingsResourcePatch = patch {
    dependsOn(
        settingsPatch(
            rootPreferences = listOf(
                IntentPreference(
                    key = SETTINGS_NAME_PREFERENCE_KEY,
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
                "morphe_settings_screen_03_feed.xml",
                "morphe_settings_screen_03_feed_bold.xml",
                "morphe_settings_screen_04_general.xml",
                "morphe_settings_screen_04_general_bold.xml",
                "morphe_settings_screen_05_player.xml",
                "morphe_settings_screen_05_player_bold.xml",
                "morphe_settings_screen_09_return_youtube_dislike.xml",
                "morphe_settings_screen_09_return_youtube_dislike_bold.xml",
                "morphe_settings_screen_10_sponsorblock.xml",
                "morphe_settings_screen_10_sponsorblock_bold.xml",
                "morphe_settings_screen_11_misc.xml",
                "morphe_settings_screen_11_misc_bold.xml",
                "morphe_settings_screen_13_scrobbling.xml",
                "morphe_settings_screen_13_scrobbling_bold.xml",
                "morphe_settings_screen_14_lyrics.xml",
                "morphe_settings_screen_14_lyrics_bold.xml"
            ),
            ResourceGroup("layout",
                "morphe_preference_with_icon.xml",
                "morphe_color_dot_widget.xml"
            )
        )

        // Set the style for the Morphe settings to follow the style of the music settings,
        // namely: action bar height, menu item padding and remove horizontal dividers.
        val targetResource = "values/styles.xml"
        readXml(inputStreamFromBundledResource("settings/music", targetResource)!!).use { source ->
            resourceXml("res/$targetResource").use { styles ->
                source.root.children.forEach { styles.root.appendCopy(it) }

                // Remove horizontal dividers from the music settings.
                styles.root.children.forEach { style ->
                    style.children("item").forEach { item ->
                        val name = item.attribute("name").orEmpty()
                        if ((name.endsWith("allowDividerAbove") || name.endsWith("allowDividerBelow")) &&
                            item.text == "true"
                        ) {
                            item.text = "false"
                        }
                    }
                }
            }
        }
    }
}

val settingsPatch = patch(
    description = "Adds settings for Morphe to YouTube Music."
) {
    dependsOn(
        sharedExtensionPatch,
        settingsResourcePatch,
        addResourcesPatch,
        versionCheckPatch,
        removeLinkVerification,
        crashLogPatch(
            mainActivityFingerprint = MusicActivityOnCreateFingerprint
        ),
        experimentalAppNoticePatch(
            mainActivityFingerprint = youTubeMusicApplicationInitOnCreateHook.fingerprint,
            recommendedAppVersion = COMPATIBILITY_YOUTUBE_MUSIC.targets.first { !it.isExperimental }.version!!
        ),
        patchesUpdateNoticePatch(
            mainActivityFingerprint = youTubeMusicApplicationInitOnCreateHook.fingerprint,
            changelogScope = "YouTube Music"
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
            SwitchPreference("morphe_show_menu_icons"),
            customSettingsNamePreference()
        )

        PreferenceScreen.MISC.addPreferences(
            NonInteractivePreference(
                key = "morphe_settings_new_indicator_days",
                tag = "app.morphe.extension.shared.settings.preference.SeekBarPreference",
                selectable = true
            ),
            TextPreference(
                key = null,
                titleKey = "morphe_pref_import_export_title",
                summaryKey = "morphe_pref_import_export_summary",
                inputType = InputType.TEXT_MULTI_LINE,
                tag = "app.morphe.extension.shared.settings.preference.ImportExportPreference"
            ),
            ListPreference(
                key = "morphe_language",
                tag = "app.morphe.extension.shared.settings.preference.SortedListPreference"
            ),
            ListPreference("morphe_patches_update_notice"),
            NonInteractivePreference(
                key = "morphe_music_app_language",
                summaryKey = null,
                tag = "app.morphe.extension.music.settings.preference.AppLanguagePreference",
                selectable = true
            )
        )

        SettingsHeadersOnCreatePreferencesFingerprint.let {
            val peerMethod = it.instructionMatches.first().methodReference
            val fragmentField = it.instructionMatches.last()
                .fieldReference

            // 9.44+ overwrites the peer register before returning, so the peer is
            // read again from the unchanged p0.
            it.method.cloneParameters().apply {
                val insertIndex = implementation!!.instructions.size - 1
                val registerProvider = getFreeRegisterProvider(insertIndex, 3, p0Register)
                val screenRegister = registerProvider.getFreeRegister()
                val preferenceRegister = registerProvider.getFreeRegister()
                val nameRegister = registerProvider.getFreeRegister()

                addInstructionsWithLabels(
                    insertIndex,
                    customSettingsNameInstructions(
                        getPreferenceScreen = """
                            invoke-virtual { p0 }, $peerMethod
                            move-result-object v$screenRegister
                            iget-object v$screenRegister, v$screenRegister, $fragmentField
                            invoke-virtual { v$screenRegister }, $SETTINGS_HEADERS_FRAGMENT_CLASS->getPreferenceScreen()Landroidx/preference/PreferenceScreen;
                            move-result-object v$screenRegister
                        """,
                        screenRegister = screenRegister,
                        preferenceRegister = preferenceRegister,
                        nameRegister = nameRegister
                    )
                )
            }

            // Other patches hook the cloned method.
            it.clearMatch()
        }

        modifyActivityForSettingsInjection(
            GoogleApiActivityOnCreateFingerprint,
            MUSIC_ACTIVITY_HOOK_CLASS,
            true
        )

        // The context is set before this, since the extension hooks are added in finalize.
        youTubeMusicApplicationInitOnCreateHook.fingerprint.method.addInstruction(
            0,
            "invoke-static {}, $MUSIC_ACTIVITY_HOOK_CLASS->onMainActivityCreate()V"
        )

        BoldIconsFeatureFlagFingerprint.matchAll().forEach {
            it.method.insertLiteralOverride(
                it.instructionMatches.first().index,
                "$MUSIC_ACTIVITY_HOOK_CLASS->useBoldIcons(Z)Z"
            )
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
    private val miniplayerPreferences = mutableSetOf<BasePreference>()

    /**
     * Adds preferences to the Miniplayer screen of the Player screen,
     * which is added with the first preferences.
     */
    fun addMiniplayerPreferences(vararg preferences: BasePreference) {
        if (miniplayerPreferences.isEmpty()) {
            PLAYER.addPreferences(
                PreferenceScreenPreference(
                    key = "morphe_music_miniplayer_screen",
                    sorting = Sorting.UNSORTED,
                    preferences = miniplayerPreferences,
                )
            )
        }
        miniplayerPreferences.addAll(preferences)
    }

    val ADS = Screen(
        key = "morphe_settings_music_screen_1_ads",
        summaryKey = null,
        icon = "@drawable/morphe_settings_screen_01_ads",
        iconBold = "@drawable/morphe_settings_screen_01_ads_bold",
        layout = "@layout/morphe_preference_with_icon"
    )
    val FEED = Screen(
        key = "morphe_settings_music_screen_2_feed",
        summaryKey = null,
        icon = "@drawable/morphe_settings_screen_03_feed",
        iconBold = "@drawable/morphe_settings_screen_03_feed_bold",
        layout = "@layout/morphe_preference_with_icon"
    )
    val GENERAL = Screen(
        key = "morphe_settings_music_screen_3_general",
        summaryKey = null,
        icon = "@drawable/morphe_settings_screen_04_general",
        iconBold = "@drawable/morphe_settings_screen_04_general_bold",
        layout = "@layout/morphe_preference_with_icon"
    )
    val PLAYER = Screen(
        key = "morphe_settings_music_screen_4_player",
        summaryKey = null,
        icon = "@drawable/morphe_settings_screen_05_player",
        iconBold = "@drawable/morphe_settings_screen_05_player_bold",
        layout = "@layout/morphe_preference_with_icon"
    )
    val LYRICS = Screen(
        key = "morphe_settings_music_screen_5_lyrics",
        summaryKey = null,
        icon = "@drawable/morphe_settings_screen_14_lyrics",
        iconBold = "@drawable/morphe_settings_screen_14_lyrics_bold",
        layout = "@layout/morphe_preference_with_icon",
        sorting = Sorting.UNSORTED
    )
    val SCROBBLING = Screen(
        key = "morphe_settings_music_screen_6_scrobbling",
        summaryKey = null,
        icon = "@drawable/morphe_settings_screen_13_scrobbling",
        iconBold = "@drawable/morphe_settings_screen_13_scrobbling_bold",
        layout = "@layout/morphe_preference_with_icon",
        sorting = Sorting.UNSORTED
    )
    val RETURN_YOUTUBE_DISLIKE = Screen(
        key = "morphe_settings_music_screen_7_return_youtube_dislike",
        summaryKey = null,
        icon = "@drawable/morphe_settings_screen_09_return_youtube_dislike",
        iconBold = "@drawable/morphe_settings_screen_09_return_youtube_dislike_bold",
        layout = "@layout/morphe_preference_with_icon",
        sorting = Sorting.UNSORTED
    )
    val SPONSORBLOCK = Screen(
        key = "morphe_settings_music_screen_8_sponsorblock",
        summaryKey = null,
        icon = "@drawable/morphe_settings_screen_10_sponsorblock",
        iconBold = "@drawable/morphe_settings_screen_10_sponsorblock_bold",
        layout = "@layout/morphe_preference_with_icon",
        sorting = Sorting.UNSORTED
    )
    val MISC = Screen(
        key = "morphe_settings_music_screen_9_misc",
        summaryKey = null,
        icon = "@drawable/morphe_settings_screen_11_misc",
        iconBold = "@drawable/morphe_settings_screen_11_misc_bold",
        layout = "@layout/morphe_preference_with_icon"
    )

    override fun commit(screen: PreferenceScreenPreference) {
        preferences += screen
    }
}
