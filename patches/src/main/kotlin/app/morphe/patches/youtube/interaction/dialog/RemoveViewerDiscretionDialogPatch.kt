package app.morphe.patches.youtube.interaction.dialog

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.reddit.utils.compatibility.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.BackgroundPlaybackManagerShortsFingerprint
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

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

    compatibleWith(COMPATIBILITY_YOUTUBE)

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
                    "invoke-static { v$dialogRegister }, $EXTENSION_CLASS_DESCRIPTOR->" +
                            "confirmDialog(Landroid/app/AlertDialog;)V",
                )
            }
        }

        CreateModernDialogFingerprint.let {
            it.method.apply {
                val showDialogIndex = it.instructionMatches.last().index
                val dialogRegister = getInstruction<FiveRegisterInstruction>(showDialogIndex).registerC

                replaceInstructions(
                    showDialogIndex,
                    "invoke-static { v$dialogRegister }, $EXTENSION_CLASS_DESCRIPTOR->" +
                            "confirmDialog(Landroid/app/AlertDialog\$Builder;)Landroid/app/AlertDialog;",
                )

                val dialogStyleIndex = it.instructionMatches.first().index
                val dialogStyleRegister = getInstruction<OneRegisterInstruction>(dialogStyleIndex).registerA

                addInstructions(
                    dialogStyleIndex + 1,
                    """
                        invoke-static { v$dialogStyleRegister }, $EXTENSION_CLASS_DESCRIPTOR->disableModernDialog(Z)Z
                        move-result v$dialogStyleRegister
                    """
                )
            }
        }

        val PlayabilityStatusFingerprint = Fingerprint(
            accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
            returnType = "Z",
            parameters = listOf(PlayabilityStatusEnumFingerprint.originalClassDef.type),
            custom = { method, _ ->
                // There's another similar method that's difficult to identify with a typical fingerprint.
                // Instruction counter is used to identify the target method.
                method.implementation!!.instructions.count() < 10
            }
        )

        PlayabilityStatusFingerprint.match(
            BackgroundPlaybackManagerShortsFingerprint.originalClassDef
        ).method.addInstruction(
            0,
            "invoke-static { p0 }, $EXTENSION_CLASS_DESCRIPTOR->" +
                    "setPlayabilityStatus(Ljava/lang/Enum;)V"
        )
    }
}
