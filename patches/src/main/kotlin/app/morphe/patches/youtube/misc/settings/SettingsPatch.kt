package app.morphe.patches.youtube.misc.settings

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.all.misc.packagename.setOrGetFallbackPackageName
import app.morphe.patches.all.misc.resources.addAppResources
import app.morphe.patches.all.misc.resources.addResourcesPatch
import app.morphe.patches.shared.BoldIconsFeatureFlagFingerprint
import app.morphe.patches.shared.layout.branding.addLicensePatch
import app.morphe.patches.shared.misc.mapping.resourceMappingPatch
import app.morphe.patches.shared.misc.settings.overrideThemeColors
import app.morphe.patches.shared.misc.settings.preference.BasePreference
import app.morphe.patches.shared.misc.settings.preference.BasePreferenceScreen
import app.morphe.patches.shared.misc.settings.preference.InputType
import app.morphe.patches.shared.misc.settings.preference.IntentPreference
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.patches.shared.misc.settings.preference.NonInteractivePreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceCategory
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference.Sorting
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.shared.misc.settings.preference.TextPreference
import app.morphe.patches.shared.misc.settings.settingsPatch
import app.morphe.patches.youtube.misc.check.checkEnvironmentPatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.fix.contentprovider.fixContentProviderPatch
import app.morphe.patches.youtube.misc.fix.playbackspeed.fixPlaybackSpeedWhilePlayingPatch
import app.morphe.patches.youtube.misc.playservice.is_19_34_or_greater
import app.morphe.patches.youtube.misc.playservice.is_20_31_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.util.ResourceGroup
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.copyResources
import app.morphe.util.findElementByAttributeValueOrThrow
import app.morphe.util.findInstructionIndicesReversedOrThrow
import app.morphe.util.insertLiteralOverride
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.util.MethodUtil

private const val BASE_ACTIVITY_HOOK_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/shared/settings/BaseActivityHook;"
private const val YOUTUBE_ACTIVITY_HOOK_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/youtube/settings/YouTubeActivityHook;"

private val preferences = mutableSetOf<BasePreference>()

private val settingsResourcePatch = resourcePatch {
    dependsOn(
        resourceMappingPatch,
        settingsPatch(
            rootPreferences = listOf(
                IntentPreference(
                    titleKey = "morphe_settings_title",
                    summaryKey = null,
                    intent = newIntent("morphe_settings_intent")
                ) to "settings_fragment",

                PreferenceCategory(
                    titleKey = "morphe_settings_title",
                    layout = "@layout/preference_group_title",
                    preferences = setOf(
                        IntentPreference(
                            titleKey = "morphe_settings_submenu_title",
                            summaryKey = null,
                            icon = "@drawable/morphe_settings_icon_dynamic",
                            layout = "@layout/preference_with_icon",
                            intent = newIntent("morphe_settings_intent")
                        )
                    )
                ) to "settings_fragment_cairo"
            ),
            preferences = preferences
        )
    )

    execute {
        // Use same colors as stock YouTube.
        overrideThemeColors("@color/yt_white1", "@color/yt_black3")

        copyResources(
            "settings",
            ResourceGroup("drawable",
                "morphe_settings_icon_dynamic.xml",
                "morphe_settings_icon.xml",
                "morphe_settings_icon_bold.xml",
                "morphe_settings_screen_00_about.xml",
                "morphe_settings_screen_00_about_bold.xml",
                "morphe_settings_screen_01_ads.xml",
                "morphe_settings_screen_01_ads_bold.xml",
                "morphe_settings_screen_02_alt_thumbnails.xml",
                "morphe_settings_screen_02_alt_thumbnails_bold.xml",
                "morphe_settings_screen_03_feed.xml",
                "morphe_settings_screen_03_feed_bold.xml",
                "morphe_settings_screen_04_general.xml",
                "morphe_settings_screen_04_general_bold.xml",
                "morphe_settings_screen_05_player.xml",
                "morphe_settings_screen_05_player_bold.xml",
                "morphe_settings_screen_06_shorts.xml",
                "morphe_settings_screen_06_shorts_bold.xml",
                "morphe_settings_screen_07_seekbar.xml",
                "morphe_settings_screen_07_seekbar_bold.xml",
                "morphe_settings_screen_08_swipe_controls.xml",
                "morphe_settings_screen_08_swipe_controls_bold.xml",
                "morphe_settings_screen_09_return_youtube_dislike.xml",
                "morphe_settings_screen_09_return_youtube_dislike_bold.xml",
                "morphe_settings_screen_10_sponsorblock.xml",
                "morphe_settings_screen_10_sponsorblock_bold.xml",
                "morphe_settings_screen_11_misc.xml",
                "morphe_settings_screen_11_misc_bold.xml",
                "morphe_settings_screen_12_video.xml",
                "morphe_settings_screen_12_video_bold.xml",
            )
        )

        // Remove horizontal divider from the settings Preferences
        // To better match the appearance of the stock YouTube settings.
        document("res/values/styles.xml").use { document ->
            val childNodes = document.childNodes

            arrayOf(
                "Theme.YouTube.Settings",
                "Theme.YouTube.Settings.Dark",
            ).forEach { value ->
                val listDividerNode = document.createElement("item")
                listDividerNode.setAttribute("name", "android:listDivider")
                listDividerNode.appendChild(document.createTextNode("@null"))

                childNodes.findElementByAttributeValueOrThrow(
                    "name",
                    value,
                ).appendChild(listDividerNode)
            }
        }

        // Modify the manifest to enhance LicenseActivity behavior:
        // 1. Add a data intent filter with MIME type "text/plain".
        //    Some devices crash if undeclared data is passed to an intent,
        //    and this change appears to fix the issue.
        // 2. Add android:configChanges="orientation|screenSize|keyboardHidden".
        //    This prevents the activity from being recreated on configuration changes
        //    (e.g., screen rotation), preserving its current state and fragment.
        document("AndroidManifest.xml").use { document ->
            val licenseElement = document.childNodes.findElementByAttributeValueOrThrow(
                "android:name",
                "com.google.android.libraries.social.licenses.LicenseActivity",
            )

            licenseElement.setAttribute(
                "android:configChanges",
                "orientation|screenSize|keyboardHidden"
            )

            val mimeType = document.createElement("data")
            mimeType.setAttribute("android:mimeType", "text/plain")

            val intentFilter = document.createElement("intent-filter")
            intentFilter.appendChild(mimeType)

            licenseElement.appendChild(intentFilter)
        }
    }
}

val settingsPatch = bytecodePatch(
    description = "Adds settings for Morphe to YouTube.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsResourcePatch,
        addResourcesPatch,
        versionCheckPatch,
        fixPlaybackSpeedWhilePlayingPatch,
        fixContentProviderPatch,
        // Currently there is no easy way to make a mandatory patch,
        // so for now this is a dependent of this patch.
        checkEnvironmentPatch,
        addLicensePatch
    )

    execute {
        addAppResources("shared-youtube")
        addAppResources("youtube")

        // Add an "About" preference to the top.
        preferences += NonInteractivePreference(
            key = "morphe_settings_screen_00_about",
            icon = "@drawable/morphe_settings_screen_00_about",
            iconBold = "@drawable/morphe_settings_screen_00_about_bold",
            layout = "@layout/preference_with_icon",
            summaryKey = null,
            tag = "app.morphe.extension.shared.settings.preference.MorpheAboutPreference",
            selectable = true,
        )

        if (is_19_34_or_greater) {
            PreferenceScreen.GENERAL_LAYOUT.addPreferences(
                SwitchPreference("morphe_restore_old_settings_menus")
            )
        }

        PreferenceScreen.GENERAL_LAYOUT.addPreferences(
            SwitchPreference("morphe_settings_search_history"),
        )

        PreferenceScreen.GENERAL_LAYOUT.addPreferences(
            if (is_20_31_or_greater) {
                PreferenceCategory(
                    titleKey = null,
                    sorting = Sorting.UNSORTED,
                    tag = "app.morphe.extension.shared.settings.preference.NoTitlePreferenceCategory",
                    preferences = setOf(
                        SwitchPreference("morphe_show_menu_icons"),
                        SwitchPreference("morphe_settings_disable_bold_icons")
                    )
                )
            } else {
                SwitchPreference("morphe_show_menu_icons")
            }
        )

        PreferenceScreen.MISC.addPreferences(
            TextPreference(
                key = null,
                titleKey = "morphe_pref_import_export_title",
                summaryKey = "morphe_pref_import_export_summary",
                inputType = InputType.TEXT_MULTI_LINE,
                tag = "app.morphe.extension.shared.settings.preference.ImportExportPreference",
            ),
            ListPreference(
                key = "morphe_language",
                tag = "app.morphe.extension.shared.settings.preference.SortedListPreference"
            )
        )

        // Update shared dark mode status based on YT theme.
        // This is needed because YT allows forcing light/dark mode
        // which then differs from the system dark mode status.
        SetThemeFingerprint.method.apply {
            findInstructionIndicesReversedOrThrow(Opcode.RETURN_OBJECT).forEach { index ->
                val register = getInstruction<OneRegisterInstruction>(index).registerA
                addInstructionsAtControlFlowLabel(
                    index,
                    "invoke-static { v$register }, ${YOUTUBE_ACTIVITY_HOOK_CLASS_DESCRIPTOR}->updateLightDarkModeStatus(Ljava/lang/Enum;)V",
                )
            }
        }

        // Add setting to force Cairo settings fragment on/off.
        CairoFragmentConfigFingerprint.method.insertLiteralOverride(
            CairoFragmentConfigFingerprint.instructionMatches.first().index,
            "$YOUTUBE_ACTIVITY_HOOK_CLASS_DESCRIPTOR->useCairoSettingsFragment(Z)Z"
        )

        // Bold icon resources are found starting in 20.23, but many YT icons are not bold.
        // 20.31 is the first version that seems to have all the bold icons.
        if (is_20_31_or_greater) {
            BoldIconsFeatureFlagFingerprint.let {
                it.method.insertLiteralOverride(
                    it.instructionMatches.first().index,
                    "$YOUTUBE_ACTIVITY_HOOK_CLASS_DESCRIPTOR->useBoldIcons(Z)Z"
                )
            }
        }

        modifyActivityForSettingsInjection(
            LicenseActivityOnCreateFingerprint.classDef,
            LicenseActivityOnCreateFingerprint.method,
            YOUTUBE_ACTIVITY_HOOK_CLASS_DESCRIPTOR,
            false
        )
    }

    finalize {
        PreferenceScreen.close()
    }
}

/**
 * Modifies the activity to show Morphe settings instead of it's original purpose.
 */
internal fun modifyActivityForSettingsInjection(
    activityOnCreateClass: MutableClass,
    activityOnCreateMethod: MutableMethod,
    extensionClassType: String,
    isYouTubeMusic: Boolean
) {
    // Modify Activity and remove all existing layout code.
    // Must modify an existing activity and cannot add a new activity to the manifest,
    // as that fails for root installations.
    activityOnCreateMethod.addInstructions(
        0,
        """
            invoke-super { p0, p1 }, ${activityOnCreateClass.superclass}->onCreate(Landroid/os/Bundle;)V
            invoke-static { p0 }, $extensionClassType->initialize(Landroid/app/Activity;)V
            return-void
        """
    )

    // Remove other methods as they will break as the onCreate method is modified above.
    activityOnCreateClass.apply {
        methods.removeIf { it != activityOnCreateMethod && !MethodUtil.isConstructor(it) }
    }

    // Override base context to allow using Morphe specific settings.
    ImmutableMethod(
        activityOnCreateClass.type,
        "attachBaseContext",
        listOf(ImmutableMethodParameter("Landroid/content/Context;", null, null)),
        "V",
        AccessFlags.PROTECTED.value,
        null,
        null,
        MutableMethodImplementation(3),
    ).toMutable().apply {
        addInstructions(
            """
                invoke-static { p1 }, $BASE_ACTIVITY_HOOK_CLASS_DESCRIPTOR->getAttachBaseContext(Landroid/content/Context;)Landroid/content/Context;
                move-result-object p1
                invoke-super { p0, p1 }, ${activityOnCreateClass.superclass}->attachBaseContext(Landroid/content/Context;)V
                return-void
            """
        )
    }.let(activityOnCreateClass.methods::add)

    // Override finish() to intercept back gesture.
    ImmutableMethod(
        activityOnCreateClass.type,
        if (isYouTubeMusic) "finish" else "onBackPressed",
        emptyList(),
        "V",
        AccessFlags.PUBLIC.value,
        null,
        null,
        MutableMethodImplementation(3),
    ).toMutable().apply {
        // Slightly different hooks are needed, otherwise the back button can behave wrong.
        val extensionMethodName = if (isYouTubeMusic) "handleFinish" else "handleBackPress"
        val invokeFinishOpcode = if (isYouTubeMusic) "invoke-super" else "invoke-virtual"

        addInstructions(
            """
                invoke-static {}, $extensionClassType->$extensionMethodName()Z
                move-result v0
                if-nez v0, :search_handled
                $invokeFinishOpcode { p0 }, Landroid/app/Activity;->finish()V
                :search_handled
                return-void
            """
        )
    }.let(activityOnCreateClass.methods::add)
}

/**
 * Creates an intent to open Morphe settings.
 */
fun newIntent(settingsName: String) = IntentPreference.Intent(
    data = settingsName,
    targetClass = "com.google.android.libraries.social.licenses.LicenseActivity",
) {
    // The package name change has to be reflected in the intent.
    setOrGetFallbackPackageName("com.google.android.youtube")
}

object PreferenceScreen : BasePreferenceScreen() {
    // Sort screens in the root menu by key, to not scatter related items apart
    // (sorting key is set in morphe_prefs.xml).
    // If no preferences are added to a screen, the screen will not be added to the settings.
    val ADS = Screen(
        key = "morphe_settings_screen_01_ads",
        summaryKey = null,
        icon = "@drawable/morphe_settings_screen_01_ads",
        iconBold = "@drawable/morphe_settings_screen_01_ads_bold",
        layout = "@layout/preference_with_icon",
    )
    val ALTERNATIVE_THUMBNAILS = Screen(
        key = "morphe_settings_screen_02_alt_thumbnails",
        summaryKey = null,
        icon = "@drawable/morphe_settings_screen_02_alt_thumbnails",
        iconBold = "@drawable/morphe_settings_screen_02_alt_thumbnails_bold",
        layout = "@layout/preference_with_icon",
        sorting = Sorting.UNSORTED,
    )
    val FEED = Screen(
        key = "morphe_settings_screen_03_feed",
        summaryKey = null,
        icon = "@drawable/morphe_settings_screen_03_feed",
        iconBold = "@drawable/morphe_settings_screen_03_feed_bold",
        layout = "@layout/preference_with_icon",
    )
    val GENERAL_LAYOUT = Screen(
        key = "morphe_settings_screen_04_general",
        summaryKey = null,
        icon = "@drawable/morphe_settings_screen_04_general",
        iconBold = "@drawable/morphe_settings_screen_04_general_bold",
        layout = "@layout/preference_with_icon",
    )
    val PLAYER = Screen(
        key = "morphe_settings_screen_05_player",
        summaryKey = null,
        icon = "@drawable/morphe_settings_screen_05_player",
        iconBold = "@drawable/morphe_settings_screen_05_player_bold",
        layout = "@layout/preference_with_icon",
    )
    val SHORTS = Screen(
        key = "morphe_settings_screen_06_shorts",
        summaryKey = null,
        icon = "@drawable/morphe_settings_screen_06_shorts",
        iconBold = "@drawable/morphe_settings_screen_06_shorts_bold",
        layout = "@layout/preference_with_icon",
    )
    val SEEKBAR = Screen(
        key = "morphe_settings_screen_07_seekbar",
        summaryKey = null,
        icon = "@drawable/morphe_settings_screen_07_seekbar",
        iconBold = "@drawable/morphe_settings_screen_07_seekbar_bold",
        layout = "@layout/preference_with_icon",
    )
    val SWIPE_CONTROLS = Screen(
        key = "morphe_settings_screen_08_swipe_controls",
        summaryKey = null,
        icon = "@drawable/morphe_settings_screen_08_swipe_controls",
        iconBold = "@drawable/morphe_settings_screen_08_swipe_controls_bold",
        layout = "@layout/preference_with_icon",
        sorting = Sorting.UNSORTED,
    )
    val RETURN_YOUTUBE_DISLIKE = Screen(
        key = "morphe_settings_screen_09_return_youtube_dislike",
        summaryKey = null,
        icon = "@drawable/morphe_settings_screen_09_return_youtube_dislike",
        iconBold = "@drawable/morphe_settings_screen_09_return_youtube_dislike_bold",
        layout = "@layout/preference_with_icon",
        sorting = Sorting.UNSORTED,
    )
    val SPONSORBLOCK = Screen(
        key = "morphe_settings_screen_10_sponsorblock",
        summaryKey = null,
        icon = "@drawable/morphe_settings_screen_10_sponsorblock",
        iconBold = "@drawable/morphe_settings_screen_10_sponsorblock_bold",
        layout = "@layout/preference_with_icon",
        sorting = Sorting.UNSORTED,
    )
    val MISC = Screen(
        key = "morphe_settings_screen_11_misc",
        summaryKey = null,
        icon = "@drawable/morphe_settings_screen_11_misc",
        iconBold = "@drawable/morphe_settings_screen_11_misc_bold",
        layout = "@layout/preference_with_icon",
    )
    val VIDEO = Screen(
        key = "morphe_settings_screen_12_video",
        summaryKey = null,
        icon = "@drawable/morphe_settings_screen_12_video",
        iconBold = "@drawable/morphe_settings_screen_12_video_bold",
        layout = "@layout/preference_with_icon",
        sorting = Sorting.BY_KEY,
    )

    override fun commit(screen: PreferenceScreenPreference) {
        preferences += screen
    }
}
