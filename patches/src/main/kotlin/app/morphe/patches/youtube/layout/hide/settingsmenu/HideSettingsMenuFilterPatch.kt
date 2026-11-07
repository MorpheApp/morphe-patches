/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2029
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.hide.settingsmenu

import app.morphe.patcher.patch.patch
import app.morphe.patches.shared.misc.settings.preference.NonInteractivePreference
import app.morphe.patches.shared.misc.settingsmenu.HIDE_MATCHING_METHOD
import app.morphe.patches.shared.misc.settingsmenu.SETTINGS_MENU_FILTER_CLASS
import app.morphe.patches.shared.misc.settingsmenu.injectHideMatchingHelper
import app.morphe.patches.shared.misc.settingsmenu.injectSettingsMenuFilterHook
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.fix.preference.addPreferenceScreenHook
import app.morphe.patches.youtube.misc.fix.preference.preferenceScreenHookPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/SettingsMenuFilterPatch;"

@Suppress("unused")
val hideSettingsMenuFilterPatch = patch(
    name = "Settings menu filter",
    description = "Adds an option to hide items on the standard YouTube settings screen by their visible name."
) {
    dependsOn(
        settingsPatch,
        sharedExtensionPatch,
        preferenceScreenHookPatch
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.GENERAL.addPreferences(
            NonInteractivePreference(
                key = "morphe_settings_menu_filter",
                titleKey = "morphe_settings_menu_filter_screen_title",
                summaryKey = "morphe_settings_menu_filter_screen_summary",
                tag = "app.morphe.extension.shared.patches.SettingsMenuFilterPickerPreference",
                selectable = true
            )
        )

        injectSettingsMenuFilterHook(EXTENSION_CLASS)
        injectHideMatchingHelper()

        addPreferenceScreenHook(2) { screenRegister, (needlesRegister, nullRegister) ->
            """
                invoke-static { }, $EXTENSION_CLASS->getNeedles()[Ljava/lang/String;
                move-result-object v$needlesRegister
                if-eqz v$needlesRegister, :ignore

                invoke-static { }, $SETTINGS_MENU_FILTER_CLASS->beginCapture()V

                const/4 v$nullRegister, 0x0
                invoke-virtual { v$screenRegister, v$needlesRegister, v$nullRegister }, $HIDE_MATCHING_METHOD

                invoke-static { }, $SETTINGS_MENU_FILTER_CLASS->endCapture()V

                :ignore
                nop
            """
        }
    }
}
