/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.patches.youtube.interaction.seekbar

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.PreferenceCategory
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference.Sorting
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.findInstructionIndicesReversedOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val EXTENSION_CLASS_DESCRIPTOR = "Lapp/morphe/extension/youtube/patches/LivestreamDvrPatch;"

@Suppress("unused")
val livestreamDvrPatch = bytecodePatch(
    description = "Enables video seeking on live streams that have disabled DVR.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
    )

    execute {
        PreferenceScreen.SEEKBAR.addPreferences(
            PreferenceCategory(
                titleKey = null,
                sorting = Sorting.UNSORTED,
                tag = "app.morphe.extension.shared.settings.preference.NoTitlePreferenceCategory",
                preferences = setOf(
                    SwitchPreference("morphe_livestream_dvr"),
                    SwitchPreference("morphe_expand_livestream_dvr_duration")
                )
            )
        )

        VideoStreamingDataAllowSeekingFingerprint.method.apply {
            findInstructionIndicesReversedOrThrow(Opcode.RETURN).forEach { returnIndex ->
                val returnRegister = getInstruction<OneRegisterInstruction>(returnIndex).registerA

                addInstructionsAtControlFlowLabel(
                    returnIndex,
                    """
                        invoke-static { v$returnRegister }, $EXTENSION_CLASS_DESCRIPTOR->enableLivestreamDvr(Z)Z
                        move-result v$returnRegister
                    """
                )
            }
        }

        FormatStreamModelMaxDvrDurationFingerprint.method.apply {
            val index = FormatStreamModelMaxDvrDurationFingerprint.instructionMatches.last().index
            val register = getInstruction<OneRegisterInstruction>(index).registerA

            addInstructions(
                index,
                """
                    invoke-static { v$register, v${register + 1} }, $EXTENSION_CLASS_DESCRIPTOR->overrideMaxDvrDurationSec(D)D
                    move-result-wide v$register
                """
            )
        }
    }
}
