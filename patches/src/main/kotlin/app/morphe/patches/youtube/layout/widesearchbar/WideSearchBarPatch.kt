/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2221
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.widesearchbar

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.all.misc.resources.resourceMappingPatch
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playservice.is_20_31_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.misc.toolbar.hookToolBar
import app.morphe.patches.youtube.misc.toolbar.toolBarHookPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val EXTENSION_CLASS = "Lapp/morphe/extension/youtube/patches/WideSearchBarPatch;"

@Suppress("unused")
val wideSearchBarPatch = bytecodePatch(
    name = "Wide search bar",
    description = "Adds a wide search bar to the top of the home and subscription feed."
) {
    category("Layout")

    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        resourceMappingPatch,
        versionCheckPatch,
        toolBarHookPatch
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        if (!is_20_31_or_greater) {
            // Legacy targets use the app's own wide search bar, which always replaces the logo.
            PreferenceScreen.GENERAL.addPreferences(
                ListPreference(
                    key = "morphe_searchbar_type",
                    entriesKey = "morphe_searchbar_type_legacy_entries",
                    entryValuesKey = "morphe_searchbar_type_legacy_entry_values"
                )
            )

            applyLegacyWideSearchBar()
            return@execute
        }

        PreferenceScreen.GENERAL.addPreferences(
            ListPreference("morphe_searchbar_type")
        )

        hookToolBar("$EXTENSION_CLASS->setSearchButtonView")

        ActionbarRingoViewFingerprint.apply {
            arrayOf(
                instructionMatches[5],
                instructionMatches[3],
                instructionMatches[1]
            ).forEach { match ->
                val index = match.index
                val register = match.getInstruction<OneRegisterInstruction>().registerA

                method.addInstructionsAtControlFlowLabel(
                    index,
                    "invoke-static { v$register }, $EXTENSION_CLASS->" +
                            "initializeWideSearchbar(Landroid/view/View;)V"
                )
            }
        }
    }
}
