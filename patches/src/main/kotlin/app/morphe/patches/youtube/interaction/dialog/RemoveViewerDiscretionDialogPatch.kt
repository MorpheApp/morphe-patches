package app.morphe.patches.youtube.interaction.dialog

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/RemoveViewerDiscretionDialogPatch;"

val removeViewerDiscretionDialogPatch = bytecodePatch(
    name = "Remove viewer discretion dialog",
    description = "Adds an option to remove the dialog that appears when opening a video that has been age-restricted " +
            "by accepting it automatically. This does not bypass the age restriction.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.GENERAL.addPreferences(
            SwitchPreference("morphe_remove_viewer_discretion_dialog"),
        )

        fun applyPatch(instructionIndex: Int, instructionRegister: Int, method: MutableMethod) {
            method.addInstructions(
                instructionIndex,
                """
                    invoke-static { v$instructionRegister }, $EXTENSION_CLASS->hideViewDiscretionDialog(Z)Z
                    move-result v$instructionRegister
                """
            )
        }

        // region skip discretion dialog
        skipDialogFingerprint(AdultContentRunnableFingerprint.method.definingClass).let { fingerprint ->
            listOf(
                fingerprint.instructionMatches[3],
                fingerprint.instructionMatches[1],
            ).forEach { instruction ->
                val instructionIndex = instruction.index
                val instructionRegister = fingerprint.method
                    .getInstruction<OneRegisterInstruction>(instructionIndex).registerA

                applyPatch(instructionIndex + 1, instructionRegister, fingerprint.method)
            }
        }

        // endregion

        // region unlock related videos for restricted videos
        val adultContentSetPropertiesMatches = AdultContentSetPropertiesFingerprint.instructionMatches

        unlockRelatedVideosFingerprint(
            skipDialogClass = skipDialogFingerprint(AdultContentRunnableFingerprint.method.definingClass).method.definingClass,
            adultContentProperty1 = adultContentSetPropertiesMatches[0]
                .getInstruction<ReferenceInstruction>().reference.toString(),
            adultContentProperty2 = adultContentSetPropertiesMatches[2]
                .getInstruction<ReferenceInstruction>().reference.toString()
        ).let { fingerprint ->
            listOf(
                fingerprint.instructionMatches[1],
                fingerprint.instructionMatches[0],
            ).forEach { instruction ->
                val instructionIndex = instruction.index
                val instructionRegister = fingerprint.method
                    .getInstruction<TwoRegisterInstruction>(instructionIndex).registerA

                applyPatch(instructionIndex, instructionRegister, fingerprint.method)
            }
        }

        // endregion
    }
}
