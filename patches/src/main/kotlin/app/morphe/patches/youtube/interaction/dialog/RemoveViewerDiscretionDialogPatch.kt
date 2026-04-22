package app.morphe.patches.youtube.interaction.dialog

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.util.cloneMutableAndPreserveParameters
import app.morphe.util.findInstructionIndicesReversed
import app.morphe.util.getMutableMethod
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val EXTENSION_CLASS = "Lapp/morphe/extension/youtube/patches/RemoveViewerDiscretionDialogPatch;"

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

        AllowControversialContentFingerprint.apply {
            val allowControversialContentMethod = instructionMatches[2]
                .getInstruction<ReferenceInstruction>()
                .getReference<MethodReference>()!!
                .getMutableMethod()

            allowControversialContentMethod.apply {
                findInstructionIndicesReversed(Opcode.RETURN).forEach { index ->
                    addInstructionsWithLabels(
                        index,
                        """
                            invoke-static {}, $EXTENSION_CLASS->hideViewDiscretionDialog()Z
                            move-result v0
                            if-eqz v0, :show_controversial_content_confirmation_box
                            return v0
                            :show_controversial_content_confirmation_box
                            nop
                        """
                    )
                }
            }

            val allowAdultContentFingerprint = Fingerprint(
                definingClass = allowControversialContentMethod.definingClass,
                accessFlags = listOf(AccessFlags.PROTECTED, AccessFlags.FINAL),
                returnType = "Ljava/lang/Boolean;",
                parameters = listOf(),
                filters = listOf(
                    literal(0),
                    methodCall("Ljava/lang/Boolean;->valueOf(Z)Ljava/lang/Boolean;"),
                )
            )

            allowAdultContentFingerprint.method.addInstructionsWithLabels(
                0,
                """
                    invoke-static {}, $EXTENSION_CLASS->hideViewDiscretionDialog()Z
                    move-result v0
                    if-eqz v0, :show_adult_content_confirmation_box
                    sget-object v0, Ljava/lang/Boolean;->TRUE:Ljava/lang/Boolean;
                    return-object v0
                    :show_adult_content_confirmation_box
                    nop
                """
            )
        }
    }
}
