package app.morphe.patches.youtube.interaction.dialog

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
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

        fun applyPatch(method: MutableMethod, instructionIndex: Int, instructionRegister: Int) {
            method.addInstructions(
                instructionIndex,
                """
                    invoke-static { v$instructionRegister }, $EXTENSION_CLASS->hideViewDiscretionDialog(Z)Z
                    move-result v$instructionRegister
                """
            )
        }

        // region skip discretion dialog
        val skipDialogFingerprint = Fingerprint(
            accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
            returnType = "V",
            parameters = listOf("L"),
            filters = listOf(
                methodCall(
                    opcode = Opcode.INVOKE_VIRTUAL,
                    smali = "Ljava/lang/Boolean;->booleanValue()Z"
                ),
                opcode(Opcode.MOVE_RESULT, location = MatchAfterImmediately()),
                opcode(Opcode.INVOKE_VIRTUAL, location = MatchAfterWithin(2)),
                opcode(Opcode.MOVE_RESULT, location = MatchAfterImmediately()),
                methodCall(
                    opcode = Opcode.INVOKE_DIRECT,
                    name = "<init>",
                    definingClass = AdultContentRunnableFingerprint.method.definingClass,
                    location = MatchAfterWithin(3)
                )
            )
        )
        skipDialogFingerprint.let { fingerprint ->
            listOf(
                fingerprint.instructionMatches[3],
                fingerprint.instructionMatches[1],
            ).forEach { instruction ->
                val instructionIndex = instruction.index
                val instructionRegister = fingerprint.method
                    .getInstruction<OneRegisterInstruction>(instructionIndex).registerA

                applyPatch(fingerprint.method, instructionIndex + 1, instructionRegister)
            }
        }

        // endregion

        // region unlock related videos for restricted videos
        val adultContentSetPropertiesMatches = AdultContentSetPropertiesFingerprint.instructionMatches

        Fingerprint(
            classFingerprint = skipDialogFingerprint,
            accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
            returnType = "V",
            parameters = listOf("L"),
            filters = listOf(
                fieldAccess(
                    opcode = Opcode.IPUT_BOOLEAN,
                    smali = adultContentSetPropertiesMatches[0]
                        .getInstruction<ReferenceInstruction>().reference.toString()
                ),
                fieldAccess(
                    opcode = Opcode.IPUT_BOOLEAN,
                    location = MatchAfterWithin(3),
                    smali = adultContentSetPropertiesMatches[2]
                        .getInstruction<ReferenceInstruction>().reference.toString()
                )
            )
        ).let { fingerprint ->
            listOf(
                fingerprint.instructionMatches[1],
                fingerprint.instructionMatches[0],
            ).forEach { instruction ->
                val instructionIndex = instruction.index
                val instructionRegister = fingerprint.method
                    .getInstruction<TwoRegisterInstruction>(instructionIndex).registerA

                applyPatch(fingerprint.method, instructionIndex, instructionRegister)
            }
        }

        // endregion
    }
}
