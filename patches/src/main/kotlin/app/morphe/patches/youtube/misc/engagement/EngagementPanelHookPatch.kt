package app.morphe.patches.youtube.misc.engagement

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.shared.EngagementPanelControllerFingerprint
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import kotlin.properties.Delegates

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/youtube/shared/EngagementPanel;"

var panelControllerMethod: MutableMethod by Delegates.notNull()
    private set
var panelIdIndex = 0
    private set
var panelIdRegister = 0
    private set
var panelIdSmaliInstruction = ""
    private set

val engagementPanelHookPatch = bytecodePatch(
    description = "Hook to get the current engagement panel state.",
) {
    dependsOn(sharedExtensionPatch)

    execute {
        EngagementPanelControllerFingerprint.let {
            it.clearMatch()
            it.method.apply {
                val panelIdField = it.instructionMatches.last().instruction.getReference<FieldReference>()!!
                val insertIndex = it.instructionMatches[5].index

                val (freeRegister, panelRegister) =
                    with (getInstruction<TwoRegisterInstruction>(insertIndex)) {
                        Pair(registerA, registerB)
                    }

                panelControllerMethod = this
                panelIdIndex = insertIndex
                panelIdRegister = freeRegister
                panelIdSmaliInstruction =
                    "iget-object v$panelIdRegister, v$panelRegister, $panelIdField"

                addInstructions(
                    insertIndex,
                    """
                        $panelIdSmaliInstruction
                        invoke-static { v$panelIdRegister }, $EXTENSION_CLASS_DESCRIPTOR->open(Ljava/lang/String;)V
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

fun addEngagementPanelIdHook(descriptor: String) =
    panelControllerMethod.addInstructionsWithLabels(
        panelIdIndex,
        """
            $panelIdSmaliInstruction
            invoke-static { v$panelIdRegister }, $descriptor
            move-result v$panelIdRegister
            if-eqz v$panelIdRegister, :shown
            const/4 v$panelIdRegister, 0x0
            return-object v$panelIdRegister
            :shown
            nop
        """
    )
