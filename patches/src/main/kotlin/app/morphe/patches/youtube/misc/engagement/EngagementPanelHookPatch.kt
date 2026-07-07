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
import java.lang.ref.WeakReference
import kotlin.properties.Delegates

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/shared/EngagementPanel;"

lateinit var panelControllerMethodRef: WeakReference<MutableMethod>
private var panelIdIndex = -1
private var panelIdRegister = -1
private lateinit var panelIdSmaliInstruction : String

val engagementPanelHookPatch = bytecodePatch(
    description = "Hook to get the current engagement panel state.",
) {
    dependsOn(sharedExtensionPatch)

    execute {
        EngagementPanelControllerFingerprint.let {
            it.method.apply {
                val panelIdField = it.instructionMatches.last().instruction.getReference<FieldReference>()!!
                val insertIndex = it.instructionMatches[5].index

                val (freeRegister, panelRegister) =
                    with (getInstruction<TwoRegisterInstruction>(insertIndex)) {
                        registerA to registerB
                    }

                panelControllerMethodRef = WeakReference(this)
                panelIdIndex = insertIndex
                panelIdRegister = freeRegister
                panelIdSmaliInstruction =
                    "iget-object v$panelIdRegister, v$panelRegister, $panelIdField"

                addInstructions(
                    insertIndex,
                    """
                        $panelIdSmaliInstruction
                        invoke-static { v$panelIdRegister }, $EXTENSION_CLASS->open(Ljava/lang/String;)V
                    """
                )
            }
        }

        EngagementPanelUpdateFingerprint.method.addInstruction(
            0,
            "invoke-static { }, $EXTENSION_CLASS->close()V"
        )
    }
}

fun addEngagementPanelIdHook(descriptor: String) =
    panelControllerMethodRef.get()!!.addInstructionsWithLabels(
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
