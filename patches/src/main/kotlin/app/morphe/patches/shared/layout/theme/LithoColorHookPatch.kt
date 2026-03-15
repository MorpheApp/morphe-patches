/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 */

package app.morphe.patches.shared.layout.theme

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import java.lang.ref.WeakReference

private lateinit var lithoColorOverrideHookRef : WeakReference<MutableMethod>
private var lithoColorOverrideHookInsertIndex = -1
private var colorRegister = -1

fun lithoColorOverrideHook(targetMethodClass: String, targetMethodName: String) {
    lithoColorOverrideHookRef.get()!!.addInstructions(
        lithoColorOverrideHookInsertIndex,
        """
        invoke-static { v$colorRegister }, $targetMethodClass->$targetMethodName(I)I
        move-result v$colorRegister
        """
    )
    lithoColorOverrideHookInsertIndex += 2
}

val lithoColorHookPatch = bytecodePatch(
    description = "Adds a hook to set color of Litho components.",
) {
    execute {
        val method = LithoOnBoundsChangeFingerprint.method
        lithoColorOverrideHookRef = WeakReference(method)

        val setColorIndex = LithoOnBoundsChangeFingerprint.instructionMatches.last().index
        lithoColorOverrideHookInsertIndex = setColorIndex

        val instruction = method.getInstruction<FiveRegisterInstruction>(setColorIndex)
        colorRegister = instruction.registerD
    }
}