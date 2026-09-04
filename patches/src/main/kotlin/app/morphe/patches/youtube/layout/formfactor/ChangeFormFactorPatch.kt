/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.layout.formfactor

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.BasePreference
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.shared.misc.settings.preference.noTitleUnsortedPreferenceCategory
import app.morphe.patches.youtube.misc.contexthook.Endpoint
import app.morphe.patches.youtube.misc.contexthook.addClientFormFactorHook
import app.morphe.patches.youtube.misc.contexthook.clientContextHookPatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.fix.videoactionbar.restoreOldVideoActionBarPatch
import app.morphe.patches.youtube.misc.navigation.navigationBarHookPatch
import app.morphe.patches.youtube.misc.playservice.is_20_31_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.util.findFreeRegister
import app.morphe.util.registersUsed
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/ChangeFormFactorPatch;"

@Suppress("unused")
val changeFormFactorPatch = bytecodePatch(
    name = "Change form factor",
    description = "Adds an option to change the UI appearance to a phone, tablet, or automotive device.",
) {
    category("Miscellaneous")

    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        clientContextHookPatch,
        navigationBarHookPatch,
        restoreOldVideoActionBarPatch,
        versionCheckPatch
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        val preferences = mutableSetOf<BasePreference>(
            ListPreference("morphe_change_form_factor")
        )

        if (is_20_31_or_greater) {
            preferences += SwitchPreference("morphe_tablet_layout_in_player", summary = true)
        }

        PreferenceScreen.GENERAL.addPreferences(
            noTitleUnsortedPreferenceCategory(preferences)
        )

        getInnerTubeClientConfigFingerprint().let {
            it.method.apply {
                val index = it.instructionMatches.last().index
                val register = getInstruction<TwoRegisterInstruction>(index).registerA

                addInstructions(
                    index + 1,
                    """
                        invoke-static { v$register }, $EXTENSION_CLASS->getUniversalFormFactor(I)I
                        move-result v$register
                    """
                )
            }
        }

        setOf(
            Endpoint.GET_WATCH,
            Endpoint.NEXT,
            Endpoint.GUIDE,
            Endpoint.REEL,
        ).forEach { endpoint ->
            addClientFormFactorHook(
                endpoint,
                "$EXTENSION_CLASS->replaceBrokenFormFactor(I)I",
            )
        }

        RepeatedItemSectionRendererFingerprint.let {
            it.method.apply {
                val match = it.instructionMatches[1]
                val index = match.index
                val instruction = match.instruction
                val listRegister = instruction.registersUsed[0]
                val listIndexRegister = instruction.registersUsed[1]
                val freeRegister = findFreeRegister(
                    index,
                    listRegister,
                    listIndexRegister
                )

                addInstructionsWithLabels(
                    index,
                    """
                        invoke-static { v$listRegister, v$listIndexRegister }, $EXTENSION_CLASS->checkItemSectionRenderer(Ljava/util/List;I)Z
                        move-result v$freeRegister
                        if-nez v$freeRegister, :empty_list_check
                        return-void
                        :empty_list_check
                        nop
                    """
                )
            }
        }
    }
}
