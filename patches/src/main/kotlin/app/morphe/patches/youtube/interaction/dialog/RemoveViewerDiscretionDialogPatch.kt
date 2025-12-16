package app.morphe.patches.youtube.interaction.dialog

import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction

private const val EXTENSION_CLASS_DESCRIPTOR = "Lapp/morphe/extension/youtube/patches/RemoveViewerDiscretionDialogPatch;"

val removeViewerDiscretionDialogPatch = bytecodePatch(
    name = "Remove viewer discretion dialog",
    description = "Adds an option to remove the dialog that appears when opening a video that has been age-restricted " +
        "by accepting it automatically. This does not bypass the age restriction.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
    )

    compatibleWith(
        "com.google.android.youtube"(
            "19.43.41",
            "20.14.43",
            "20.21.37",
            "20.31.42",
            "20.46.41",
        )
    )

    execute {
        PreferenceScreen.GENERAL_LAYOUT.addPreferences(
            SwitchPreference("morphe_remove_viewer_discretion_dialog"),
        )

        CreateDialogFingerprint.let {
            it.method.apply {
                val showDialogIndex = it.instructionMatches.last().index
                val dialogRegister = getInstruction<FiveRegisterInstruction>(showDialogIndex).registerC

                replaceInstructions(
                    showDialogIndex,
                    "invoke-static { v$dialogRegister }, $EXTENSION_CLASS_DESCRIPTOR->confirmDialog(Landroid/app/AlertDialog;)V",
                )
            }
        }
    }
}
