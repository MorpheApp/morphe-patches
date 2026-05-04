/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.patches.shared.misc.fix.bitmap

import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.util.findMutableMethodOf
import app.morphe.util.fiveRegisters
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/shared/patches/FixRecycledBitmapPatch;"

val fixRecycledBitmapPatch = bytecodePatch(
    description = "Fixes recycled bitmap crashes by routing putBitmap through the extension class."
) {
    execute {
        // TODO: Replace this with Fingerprint.matchAll()
        classDefForEach { classDef ->
            if (classDef.type.startsWith("Lapp/morphe/extension")) return@classDefForEach

            classDef.methods.forEach { method ->
                val instructionsIterable = method.implementation?.instructions ?: return@forEach
                val targetIndices = instructionsIterable.mapIndexedNotNull { index, instruction ->
                    if (instruction.opcode.name == "invoke-virtual" && instruction is ReferenceInstruction) {
                        val ref = instruction.reference as? MethodReference
                        val isTargetMethod = ref?.definingClass == $$"Landroid/media/MediaMetadata$Builder;" &&
                                ref.name == "putBitmap" &&
                                ref.parameterTypes.joinToString("") == "Ljava/lang/String;Landroid/graphics/Bitmap;"

                        if (isTargetMethod) index else null
                    } else {
                        null
                    }
                }

                if (targetIndices.isNotEmpty()) {
                    val mutableMethod = mutableClassDefBy(classDef.type).findMutableMethodOf(method)

                    targetIndices.reversed().forEach { index ->
                        val registers = mutableMethod.fiveRegisters(index)
                        val replacementSmali =
                            $$"invoke-static {$$registers}, $$EXTENSION_CLASS->putBitmap(Landroid/media/MediaMetadata$Builder;Ljava/lang/String;Landroid/graphics/Bitmap;)Landroid/media/MediaMetadata$Builder;"

                        mutableMethod.replaceInstruction(index, replacementSmali)
                    }
                }
            }
        }
    }
}