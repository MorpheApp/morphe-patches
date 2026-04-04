package app.morphe.patches.youtube.video.livedvr

import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.findInstructionIndicesReversedOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/youtube/patches/LiveDvrPatch;"

@Suppress("unused")
val liveDvrPatch = bytecodePatch(
    name = "Force live DVR",
    description = "Forces seek-back (DVR) on live streams that do not have it enabled.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.VIDEO.addPreferences(
            SwitchPreference("morphe_force_live_dvr")
        )

        VideoStreamingDataAllowSeekingFingerprint.method.apply {
            for (returnIndex in findInstructionIndicesReversedOrThrow { opcode == Opcode.RETURN }) {
                val returnRegister = getInstruction<OneRegisterInstruction>(returnIndex).registerA
                addInstructionsAtControlFlowLabel(
                    returnIndex,
                    """
                        invoke-static { v$returnRegister }, $EXTENSION_CLASS_DESCRIPTOR->enableLiveDvr(Z)Z
                        move-result v$returnRegister
                    """,
                )
            }
        }
    }
}
