package app.morphe.patches.youtube.misc.fix.accessibility

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.removeInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.youtube.misc.playservice.is_20_51_or_greater
import app.morphe.patches.youtube.misc.playservice.is_21_28_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction

private const val BOTTOM_UI_CONTAINER =
    "Lcom/google/android/apps/youtube/app/common/ui/bottomui/BottomUiContainer;"

private object BottomUiKeyboardVisibilityFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("L"),
    filters = listOf(
        literal(45731499L),
        fieldAccess(opcode = Opcode.IGET_BOOLEAN, type = "Z", location = MatchAfterWithin(10)),
        methodCall(
            smali = "$BOTTOM_UI_CONTAINER->setVisibility(I)V",
            location = MatchAfterWithin(6)
        )
    )
)

private object BottomUiVisibilityGateFingerprint : Fingerprint(
    definingClass = BOTTOM_UI_CONTAINER,
    returnType = "V",
    parameters = listOf("Z"),
    filters = listOf(
        fieldAccess(opcode = Opcode.IPUT_BOOLEAN, definingClass = BOTTOM_UI_CONTAINER, type = "Z"),
        fieldAccess(
            opcode = Opcode.IGET,
            definingClass = BOTTOM_UI_CONTAINER,
            type = "I",
            location = MatchAfterImmediately()
        ),
        methodCall(
            smali = "$BOTTOM_UI_CONTAINER->setVisibility(I)V",
            location = MatchAfterImmediately()
        )
    )
)

internal val fixShortsAccessibilityPatch = bytecodePatch(
    description = "Fixes Shorts comments and descriptions being inaccessible to screen readers."
) {
    dependsOn(versionCheckPatch)

    execute {
        if (!is_20_51_or_greater || is_21_28_or_greater) return@execute

        val visibilityGate = BottomUiVisibilityGateFingerprint.originalMethod
        BottomUiKeyboardVisibilityFingerprint.let {
            val keyboardIndex = it.instructionMatches[1].index
            val visibilityIndex = it.instructionMatches.last().index
            val method = it.method
            val keyboardRegister = method.getInstruction<TwoRegisterInstruction>(keyboardIndex).registerA
            val containerRegister = method.getInstruction<FiveRegisterInstruction>(visibilityIndex).registerC

            // An empty BottomUiContainer can hide Shorts panels from TalkBack (#3417).
            // Backport YouTube 21.28.208's keyboard visibility gate to preserve the requested visibility.
            method.apply {
                removeInstructions(keyboardIndex + 1, visibilityIndex - keyboardIndex - 1)
                replaceInstruction(
                    keyboardIndex + 1,
                    "invoke-virtual { v$containerRegister, v$keyboardRegister }, $visibilityGate"
                )
                addInstruction(
                    keyboardIndex + 1,
                    "xor-int/lit8 v$keyboardRegister, v$keyboardRegister, 0x1"
                )
            }
        }
    }
}
