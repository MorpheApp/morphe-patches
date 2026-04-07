package app.morphe.patches.youtube.interaction.seekbar

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/youtube/patches/ExpandLivestreamDvrDurationPatch;"

val expandLivestreamDvrDurationPatch = bytecodePatch(
    description = "Expands the seekable duration of livestream DVR to 7 days.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
    )

    execute {
        PreferenceScreen.SEEKBAR.addPreferences(
            SwitchPreference("morphe_expand_livestream_dvr_duration")
        )

        FormatStreamModelMaxDvrDurationFingerprint.method.apply {
            val returnIndex = FormatStreamModelMaxDvrDurationFingerprint.instructionMatches.last().index
            val returnReg = getInstruction<OneRegisterInstruction>(returnIndex).registerA

            addInstructions(
                returnIndex,
                """
                    invoke-static { v$returnReg, v${returnReg + 1} }, $EXTENSION_CLASS_DESCRIPTOR->overrideMaxDvrDurationSec(D)D
                    move-result-wide v$returnReg
                """
            )
        }
    }
}
