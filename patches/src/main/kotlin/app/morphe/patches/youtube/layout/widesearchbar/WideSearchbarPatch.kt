/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2221
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.widesearchbar

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.all.misc.resources.resourceMappingPatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val EXTENSION_CLASS = "Lapp/morphe/extension/youtube/patches/WideSearchbarPatch;"

val wideSearchbarPatch = bytecodePatch(
    name = "Wide search bar",
    description = "Adds a wide search bar to the top of the home and subscription feed."
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        resourceMappingPatch
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.GENERAL.addPreferences(
            SwitchPreference("morphe_wide_searchbar")
        )

        ActionbarRingoViewFingerprint.apply {
            arrayOf(
                instructionMatches[5],
                instructionMatches[3],
                instructionMatches[1]
            ).forEach { match ->
                val index = match.index
                val register = match.getInstruction<OneRegisterInstruction>().registerA

                method.addInstruction(
                    index,
                    "invoke-static { v$register }, $EXTENSION_CLASS->" +
                            "initializeContainer(Landroid/view/View;)V"
                )
            }
        }

        MobileTopBarFingerprint.let {
            val index = it.instructionMatches[2].index
            val register = it.instructionMatches[2].getInstruction<OneRegisterInstruction>().registerA

            it.method.addInstruction(
                index + 1,
                "invoke-static { v$register }, $EXTENSION_CLASS->" +
                        "setSearchImageView(Landroid/view/View;)V"
            )
        }
    }
}
