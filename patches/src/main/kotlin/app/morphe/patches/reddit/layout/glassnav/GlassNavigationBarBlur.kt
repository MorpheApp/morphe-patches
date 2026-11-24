/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.glassnav

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patches.reddit.layout.autoscroll.MainActivityOnResumeFingerprint
import app.morphe.patches.reddit.misc.version.is_2026_47_0_or_greater
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private fun fillCanvasBridges(drawWithContentRef: MethodReference) {
    val drawContextMatch = LottieDrawFingerprint.instructionMatches[0]
    val drawContextRef = drawContextMatch.methodReference
    val scopeType = drawContextRef.definingClass

    val canvasMatch = LottieDrawFingerprint.instructionMatches[2]
    val canvasRef = canvasMatch.methodReference
    val isInterface = canvasMatch.instruction.opcode in listOf(Opcode.INVOKE_INTERFACE, Opcode.INVOKE_INTERFACE_RANGE)
    val canvasInvokeOp = if (isInterface) "invoke-interface" else "invoke-virtual"

    val nativeFieldMatch = LottieDrawFingerprint.instructionMatches[3]
    val nativeField = nativeFieldMatch.fieldReference

    ComposeCanvasStubFingerprint.method.addInstructions(
        0,
        """
            check-cast p0, $scopeType
            invoke-interface { p0 }, $drawContextRef
            move-result-object p0
            $canvasInvokeOp { p0 }, $canvasRef
            move-result-object p0
            return-object p0
        """
    )
    NativeCanvasStubFingerprint.method.addInstructions(
        0,
        """
            check-cast p0, ${nativeField.definingClass}
            iget-object p0, p0, $nativeField
            return-object p0
        """
    )
    SetNativeCanvasStubFingerprint.method.addInstructions(
        0,
        """
            check-cast p0, ${nativeField.definingClass}
            iput-object p1, p0, $nativeField
            return-void
        """
    )
    DrawWithContentStubFingerprint.method.addInstructions(
        0,
        """
            check-cast p0, ${drawWithContentRef.parameterTypes[0]}
            invoke-static { p0, p1 }, $drawWithContentRef
            move-result-object p0
            return-object p0
        """
    )
}

private fun hookNavContentBox() {
    val fingerprint = if (is_2026_47_0_or_greater) {
        NavContentBoxFingerprint
    } else {
        NavContentBoxLegacyFingerprint
    }
    val cMatch = fingerprint.instructionMatches[1]
    val moveResultMatch = fingerprint.instructionMatches[2]
    val vM = moveResultMatch.getInstruction<OneRegisterInstruction>().registerA
    val returnType = cMatch.methodReference.returnType

    val invokeSmali = if (vM > 15) {
        "invoke-static/range { v$vM .. v$vM }, $BLUR_EXTENSION_CLASS->contentModifier(Ljava/lang/Object;)Ljava/lang/Object;"
    } else {
        "invoke-static { v$vM }, $BLUR_EXTENSION_CLASS->contentModifier(Ljava/lang/Object;)Ljava/lang/Object;"
    }
    fingerprint.method.addInstructions(
        moveResultMatch.index + 1,
        """
            $invokeSmali
            move-result-object v$vM
            check-cast v$vM, $returnType
        """
    )
}

private fun hookMainActivity() {
    MainActivityOnResumeFingerprint.method.addInstructions(
        0,
        "invoke-static { p0 }, $BLUR_EXTENSION_CLASS->onActivityResumed(Landroid/app/Activity;)V"
    )
}

internal fun hookGlassBlur(drawWithContentRef: MethodReference) {
    fillCanvasBridges(drawWithContentRef)
    hookNavContentBox()
    hookMainActivity()
}
