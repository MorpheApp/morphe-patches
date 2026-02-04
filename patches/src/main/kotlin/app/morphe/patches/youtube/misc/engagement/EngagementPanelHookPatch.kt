package app.morphe.patches.youtube.misc.engagement

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.shared.EngagementPanelControllerFingerprint
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/youtube/shared/EngagementPanel;"

val engagementPanelHookPatch = bytecodePatch(
    description = "Hook to get the current engagement panel state.",
) {
    dependsOn(sharedExtensionPatch)

    execute {
        EngagementPanelControllerFingerprint.clearMatch()
        EngagementPanelControllerFingerprint.let {
            it.method.apply {
                val panelIdField = it.instructionMatches.last().instruction.getReference<FieldReference>()!!
                val insertIndex = it.instructionMatches[5].index

                val (freeRegister, panelRegister) =
                    with (getInstruction<TwoRegisterInstruction>(insertIndex)) {
                        Pair(registerA, registerB)
                    }

                addInstructions(
                    insertIndex,
                    """
                        iget-object v$freeRegister, v$panelRegister, $panelIdField
                        invoke-static { v$freeRegister }, $EXTENSION_CLASS_DESCRIPTOR->open(Ljava/lang/String;)V
                    """
                )
            }
        }

        EngagementPanelUpdateFingerprint.match(
            EngagementPanelControllerFingerprint.originalClassDef
        ).method.addInstruction(
            0,
            "invoke-static { }, $EXTENSION_CLASS_DESCRIPTOR->close()V"
        )
    }
}
