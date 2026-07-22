/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2029
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.layout.hide.settingsmenu

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.settings.PreferenceScreen
import app.morphe.patches.music.misc.settings.settingsPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.patches.shared.misc.settings.preference.NonInteractivePreference
import app.morphe.patches.shared.misc.settingsmenu.HIDE_MATCHING_METHOD
import app.morphe.patches.shared.misc.settingsmenu.SETTINGS_MENU_FILTER_CLASS
import app.morphe.patches.shared.misc.settingsmenu.injectHideMatchingHelper
import app.morphe.patches.shared.misc.settingsmenu.injectSettingsMenuFilterHook
import app.morphe.util.getFreeRegisterProvider
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/music/patches/SettingsMenuFilterPatch;"

private const val SETTINGS_HEADERS_FRAGMENT_CLASS =
    "Lcom/google/android/apps/youtube/music/settings/fragment/SettingsHeadersFragment;"

@Suppress("unused")
val hideSettingsMenuFilterPatch = bytecodePatch(
    name = "Settings menu filter",
    description = "Adds an option to hide items on the standard YouTube Music settings screen by their visible name."
) {
    dependsOn(
        settingsPatch,
        sharedExtensionPatch
    )

    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)

    execute {
        PreferenceScreen.GENERAL.addPreferences(
            NonInteractivePreference(
                key = "morphe_music_settings_menu_filter",
                titleKey = "morphe_settings_menu_filter_screen_title",
                summaryKey = "morphe_settings_menu_filter_screen_summary",
                tag = "app.morphe.extension.shared.patches.SettingsMenuFilterPickerPreference",
                selectable = true
            )
        )

        injectSettingsMenuFilterHook(EXTENSION_CLASS)
        injectHideMatchingHelper()

        // p0 is reassigned to the peer at method entry, so recover the fragment via peer.fragment.
        SettingsHeadersOnCreatePreferencesFingerprint.let {
            val fragmentField = it.instructionMatches.last()
                .instruction.getReference<FieldReference>()!!

            it.method.apply {
                val insertIndex = implementation!!.instructions.size - 1
                val peerRegister = it.instructionMatches[1].getInstruction<OneRegisterInstruction>().registerA
                val registerProvider = getFreeRegisterProvider(insertIndex,
                    3, peerRegister)
                val free1 = registerProvider.getFreeRegister()
                val free2 = registerProvider.getFreeRegister()
                val free3 = registerProvider.getFreeRegister()

                addInstructionsWithLabels(
                    insertIndex,
                    """
                        iget-object v$free1, v$peerRegister, $fragmentField
                        invoke-virtual { v$free1 }, $SETTINGS_HEADERS_FRAGMENT_CLASS->getPreferenceScreen()Landroidx/preference/PreferenceScreen;
                        move-result-object v$free1
                        if-eqz v$free1, :ignore

                        invoke-static { }, $EXTENSION_CLASS->getNeedles()[Ljava/lang/String;
                        move-result-object v$free2
                        if-eqz v$free2, :ignore

                        invoke-static { }, $SETTINGS_MENU_FILTER_CLASS->beginCapture()V

                        const/4 v$free3, 0x0
                        invoke-virtual { v$free1, v$free2, v$free3 }, $HIDE_MATCHING_METHOD

                        invoke-static { }, $SETTINGS_MENU_FILTER_CLASS->endCapture()V

                        :ignore
                        nop
                    """
                )
            }
        }
    }
}
