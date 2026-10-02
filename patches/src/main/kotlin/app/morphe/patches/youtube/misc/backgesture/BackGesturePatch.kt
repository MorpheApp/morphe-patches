/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3416
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.misc.backgesture

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.youtube.misc.playservice.is_20_40_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstructionOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import java.lang.ref.WeakReference

private lateinit var backPressedMethodRef: WeakReference<MutableMethod>
private var predictiveBackGestureMethodRef = WeakReference<MutableMethod>(null)

val backGesturePatch = bytecodePatch {
    dependsOn(versionCheckPatch)

    execute {
        backPressedMethodRef = WeakReference(YouTubeMainActivityOnBackPressedFingerprint.method)

        if (is_20_40_or_greater) {
            predictiveBackGestureMethodRef = WeakReference(PredictiveGesturesOnBackInvokedFingerprint.method)
        }
    }
}

fun addBackPressedHook(
    extensionClassDescriptor: String,
    methodName: String = "onBackPressed",
    afterActivityBackPressed: Boolean = false
) {
    val hook = "invoke-static { }, $extensionClassDescriptor->$methodName()V"

    backPressedMethodRef.get()!!.apply {
        if (afterActivityBackPressed) {
            // Index is found again, as other hooks can be inserted before it.
            val index = indexOfFirstInstructionOrThrow {
                opcode == Opcode.INVOKE_SUPER && getReference<MethodReference>()?.name == "onBackPressed"
            } + 1

            addInstructionsAtControlFlowLabel(index, hook)
        } else {
            addInstruction(0, hook)
        }
    }
}

fun addPredictiveBackGestureHook(
    extensionClassDescriptor: String,
    methodName: String = "onBackInvoked"
) {
    predictiveBackGestureMethodRef.get()?.addInstruction(
        0,
        "invoke-static { }, $extensionClassDescriptor->$methodName()V"
    )
}
