package app.morphe.patches.youtube.layout.toolbar

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.removeInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.shared.misc.mapping.resourceMappingPatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.shared.ToolBarButtonFingerprint
import app.morphe.util.findFreeRegister
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstruction
import app.morphe.util.indexOfFirstInstructionOrThrow
import app.morphe.util.indexOfFirstInstructionReversedOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal const val EXTENSION_CLASS_DESCRIPTOR = "Lapp/morphe/extension/youtube/patches/ToolBarPatch;"

private lateinit var toolbarMethod: MutableMethod

val toolBarHookPatch = bytecodePatch(
    description = "toolBarHookPatch"
) {
    dependsOn(
        sharedExtensionPatch,
        resourceMappingPatch
    )

    execute {
        fun indexOfGetDrawableInstruction(method: Method) =
            method.indexOfFirstInstruction {
                opcode == Opcode.INVOKE_VIRTUAL &&
                        getReference<MethodReference>()?.toString() == "Landroid/content/res/Resources;->getDrawable(I)Landroid/graphics/drawable/Drawable;"
            }

        ToolBarButtonFingerprint.method.apply {
            // TODO: Convert this into instruction filters.
            val getDrawableIndex = indexOfGetDrawableInstruction(this)
            val enumOrdinalIndex = indexOfFirstInstructionReversedOrThrow(getDrawableIndex) {
                opcode == Opcode.INVOKE_INTERFACE &&
                        getReference<MethodReference>()?.returnType == "I"
            }
            val replaceReference = getInstruction<ReferenceInstruction>(enumOrdinalIndex).reference
            val replaceRegister = getInstruction<FiveRegisterInstruction>(enumOrdinalIndex).registerC
            val enumRegister = getInstruction<FiveRegisterInstruction>(enumOrdinalIndex).registerD
            val insertIndex = enumOrdinalIndex + 1
            val freeRegister = findFreeRegister(insertIndex, enumRegister, replaceRegister)

            val imageViewIndex = indexOfFirstInstructionOrThrow(enumOrdinalIndex) {
                opcode == Opcode.IGET_OBJECT &&
                        getReference<FieldReference>()?.type == "Landroid/widget/ImageView;"
            }
            val imageViewReference = getInstruction<ReferenceInstruction>(imageViewIndex).reference

            addInstructions(
                insertIndex,
                """
                    iget-object v$freeRegister, p0, $imageViewReference
                    invoke-static {v$enumRegister, v$freeRegister}, $EXTENSION_CLASS_DESCRIPTOR->hookToolBar(Ljava/lang/Enum;Landroid/widget/ImageView;)V
                    invoke-interface {v$replaceRegister, v$enumRegister}, $replaceReference
                """
            )
            removeInstruction(enumOrdinalIndex)
        }

        toolbarMethod = ToolBarPatchFingerprint.method
    }
}

internal fun hookToolBar(descriptor: String) =
    toolbarMethod.addInstructions(
        0,
        "invoke-static {p0, p1}, $descriptor(Ljava/lang/String;Landroid/view/View;)V"
    )
