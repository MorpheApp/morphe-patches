/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 */
 
package app.morphe.patches.youtube.misc.litho.lazily

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.litho.context.EXTENSION_CONTEXT_INTERFACE
import app.morphe.patches.youtube.misc.litho.context.conversionContextPatch
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.getFreeRegisterProvider
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

internal const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/youtube/patches/LazilyConvertedElementPatch;"

private lateinit var lazilyConvertedElementLoadedMethod: MutableMethod

internal val lazilyConvertedElementHookPatch = bytecodePatch(
    description = "Hooks the LazilyConvertedElement tree node lists to the extension."
) {
    dependsOn(
        sharedExtensionPatch,
        conversionContextPatch
    )

    execute {
        TreeNodeResultListFingerprint.match(
            ComponentContextParserFingerprint.originalClassDef,
        ).method.apply {
            val insertIndex = implementation!!.instructions.lastIndex
            val listRegister = getInstruction<OneRegisterInstruction>(insertIndex).registerA

            val registerProvider = getFreeRegisterProvider(insertIndex, 1)
            val freeRegister = registerProvider.getFreeRegister()

            addInstructionsAtControlFlowLabel(
                insertIndex,
                """
                    move-object/from16 v$freeRegister, p2
                    invoke-static { v$freeRegister, v$listRegister }, $EXTENSION_CLASS_DESCRIPTOR->onTreeNodeResultLoaded(${EXTENSION_CONTEXT_INTERFACE}Ljava/util/List;)V
                """
            )
        }

        lazilyConvertedElementLoadedMethod = LazilyConvertedElementPatchFingerprint.method
    }
}

internal fun hookTreeNodeResult(descriptor: String) =
    lazilyConvertedElementLoadedMethod.addInstruction(
        0,
        "invoke-static { p0, p1 }, $descriptor(Ljava/lang/String;Ljava/util/List;)V"
    )
