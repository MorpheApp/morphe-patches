package app.morphe.patches.youtube.misc.engagement

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.shared.EngagementPanelControllerFingerprint
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import java.lang.ref.WeakReference

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/shared/EngagementPanel;"

private var panelIdField: FieldReference? = null
private var panelIdIndex = -1
private var panelIdRegister = ""
private var panelRegister = ""
lateinit var panelControllerMethodRef: WeakReference<MutableMethod>

val engagementPanelHookPatch = bytecodePatch(
    description = "Hook to get the current engagement panel state.",
) {
    dependsOn(sharedExtensionPatch)

    execute {
        EngagementPanelControllerFingerprint.let {
            it.method.apply {
                panelIdField = it.instructionMatches.last().instruction.getReference<FieldReference>()!!
                panelIdIndex = it.instructionMatches[5].index

                with(getInstruction<TwoRegisterInstruction>(panelIdIndex)) {
                    panelIdRegister = "v$registerA"
                    panelRegister = "v$registerB"
                }

                panelControllerMethodRef = WeakReference(this)

                listOf(
                    this,
                    EngagementPanelUpdateFingerprint.method
                ).forEachIndexed { index, method ->
                    var targetInstructionIndex = 0
                    var smaliIntructionPanelIdRegister = ""
                    var smaliIntructionPanelRegister = ""
                    var integrationMethodName = ""

                    if (index == 0) {
                        targetInstructionIndex = panelIdIndex
                        smaliIntructionPanelIdRegister = panelIdRegister
                        smaliIntructionPanelRegister = panelRegister
                        integrationMethodName = "open"
                    } else {
                        targetInstructionIndex = 0
                        smaliIntructionPanelIdRegister = "v0"
                        smaliIntructionPanelRegister = "p1"
                        integrationMethodName = "close"
                    }

                    method.addInstructionsWithLabels(
                        targetInstructionIndex,
                        """
                            if-eqz $smaliIntructionPanelRegister, :null_check
                            ${panelIdSmaliInstruction(smaliIntructionPanelIdRegister,  smaliIntructionPanelRegister)}
                            invoke-static { $smaliIntructionPanelIdRegister }, $EXTENSION_CLASS->$integrationMethodName(Ljava/lang/String;)V
                            :null_check
                            nop
                        """
                    )
                }
            }
        }
    }
}

fun panelIdSmaliInstruction(panelIdRegister: String, panelRegister: String) =
    "iget-object $panelIdRegister, $panelRegister, $panelIdField"

fun addEngagementPanelIdHook(descriptor: String) =
    panelControllerMethodRef.get()!!.addInstructionsWithLabels(
        panelIdIndex,
        """
            if-eqz $panelRegister, :null_check
            ${panelIdSmaliInstruction(panelIdRegister, panelRegister)}
            invoke-static { $panelIdRegister }, $descriptor
            move-result $panelIdRegister
            if-eqz $panelIdRegister, :shown
            const/4 $panelIdRegister, 0x0
            return-object $panelIdRegister
            :shown
            :null_check
            nop
        """
    )
