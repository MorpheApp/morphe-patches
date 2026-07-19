/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.interaction.seekbar

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.shared.misc.settings.preference.noTitleUnsortedPreferenceCategory
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.findInstructionIndicesReversedOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val EXTENSION_CLASS = "Lapp/morphe/extension/youtube/patches/LivestreamDVRPatch;"

@Suppress("unused")
val livestreamDVRPatch = bytecodePatch(
    description = "Enables video seeking on livestreams that have disabled DVR (Digital Video Recorder).",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
    )

    execute {
        PreferenceScreen.SEEKBAR.addPreferences(
            noTitleUnsortedPreferenceCategory(
                SwitchPreference(
                    key = "morphe_livestream_dvr",
                    summary = true,
                    tag = "app.morphe.extension.youtube.settings.preference.LivestreamDVRPreference"
                ),
                SwitchPreference(
                    key = "morphe_expand_livestream_dvr_duration",
                    summary = true,
                    tag = "app.morphe.extension.youtube.settings.preference.LivestreamDVRDurationPreference"
                )
            )
        )

        VideoStreamingDataAllowSeekingFingerprint.method.apply {
            findInstructionIndicesReversedOrThrow(Opcode.RETURN).forEach { returnIndex ->
                val returnRegister = getInstruction<OneRegisterInstruction>(returnIndex).registerA

                addInstructionsAtControlFlowLabel(
                    returnIndex,
                    """
                        invoke-static { v$returnRegister }, $EXTENSION_CLASS->enableLivestreamDVR(Z)Z
                        move-result v$returnRegister
                    """
                )
            }
        }

        FormatStreamModelMaxDVRDurationFingerprint.method.apply {
            val index = FormatStreamModelMaxDVRDurationFingerprint.instructionMatches.last().index
            val register = getInstruction<OneRegisterInstruction>(index).registerA

            addInstructions(
                index,
                """
                    invoke-static { v$register, v${register + 1} }, $EXTENSION_CLASS->overrideMaxDVRDurationSeconds(D)D
                    move-result-wide v$register
                """
            )
        }
    }
}
