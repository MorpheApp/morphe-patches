package app.morphe.patches.youtube.misc.fix.accessibility

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.removeInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.youtube.misc.playservice.is_20_51_or_greater
import app.morphe.patches.youtube.misc.playservice.is_21_28_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction

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
                removeInstructions(keyboardIndex + 1, visibilityIndex - keyboardIndex)
                addInstructions(
                    keyboardIndex + 1,
                    """
                        xor-int/lit8 v$keyboardRegister, v$keyboardRegister, 0x1
                        invoke-virtual { v$containerRegister, v$keyboardRegister }, $visibilityGate
                    """
                )
            }
        }
    }
}
