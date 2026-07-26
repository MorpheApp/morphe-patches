/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.layout.widesearchbar

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import java.util.logging.Logger

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/WideSearchbarPatch;"

val wideSearchbarPatch = bytecodePatch(
    name = "Add wide search bar",
    description = "Adds a wide search bar to the homepage, between the logo and the toolbar buttons."
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.GENERAL.addPreferences(
            SwitchPreference("morphe_wide_searchbar"),
        )

        ActionbarRingoViewFingerprint.apply {
            listOf(
                instructionMatches[1],
                instructionMatches[3],
                instructionMatches[5]
            ).reversed().forEach { match ->
                val instructionIndex = match.index
                val instructionRegister = method.getInstruction<OneRegisterInstruction>(
                    instructionIndex
                ).registerA

                method.addInstruction(
                    instructionIndex,
                    "invoke-static { v$instructionRegister }, $EXTENSION_CLASS->initializeContainer(Landroid/view/View;)V"
                )
            }
        }
    }
}
