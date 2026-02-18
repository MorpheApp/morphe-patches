/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 */

package app.morphe.patches.youtube.misc.toolbar

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.shared.misc.mapping.resourceMappingPatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.shared.ToolBarButtonFingerprint
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.findFreeRegister
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction

internal const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/youtube/patches/ToolBarPatch;"

private lateinit var toolbarMethod: MutableMethod

val toolBarHookPatch = bytecodePatch(
    description = "toolBarHookPatch"
) {
    dependsOn(
        sharedExtensionPatch,
        resourceMappingPatch
    )

    execute {
        ToolBarButtonFingerprint.let {
            it.clearMatch()
            it.method.apply {
                val enumIndex = it.instructionMatches[3].index
                val enumRegister = getInstruction<OneRegisterInstruction>(enumIndex).registerA

                val imageViewIndex = it.instructionMatches[6].index
                val imageViewReference = getInstruction<ReferenceInstruction>(imageViewIndex).reference

                val insertIndex = enumIndex + 1
                val freeRegister = findFreeRegister(insertIndex, enumRegister)

                addInstructionsAtControlFlowLabel(
                    insertIndex,
                    """
                        iget-object v$freeRegister, p0, $imageViewReference
                        invoke-static { v$enumRegister, v$freeRegister }, $EXTENSION_CLASS_DESCRIPTOR->hookToolBar(Ljava/lang/Enum;Landroid/widget/ImageView;)V
                    """
                )
            }
        }

        toolbarMethod = ToolBarPatchFingerprint.method
    }
}

internal fun hookToolBar(descriptor: String) =
    toolbarMethod.addInstructions(
        0,
        "invoke-static { p0, p1 }, $descriptor(Ljava/lang/String;Landroid/view/View;)V"
    )

