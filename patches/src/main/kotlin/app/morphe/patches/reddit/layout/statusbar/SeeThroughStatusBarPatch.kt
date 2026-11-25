/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.statusbar

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.patch
import app.morphe.patches.reddit.layout.glassnav.MODIFIER_INTERFACE
import app.morphe.patches.reddit.layout.glassnav.glassNavigationBarPatch
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.misc.version.is_2026_45_0_or_greater
import app.morphe.patches.reddit.misc.version.versionCheckPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.setExtensionIsPatchIncluded
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import java.util.logging.Logger

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/reddit/patches/SeeThroughStatusBarPatch;"

@Suppress("unused")
val seeThroughStatusBarPatch = patch(
    name = "See-through status bar",
    description = "Adds an option to let the home feed scroll up under the status bar, fading into it."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(settingsPatch, versionCheckPatch, glassNavigationBarPatch)

    execute {
        if (!is_2026_45_0_or_greater) {
            return@execute Logger.getLogger(this::class.java.name).warning(
                "'See-through status bar' requires Reddit 2026.45.0+"
            )
        }

        val a14Match = HomeScaffoldFingerprint.instructionMatches[14]
        val a14ReturnType = a14Match.methodReference.returnType
        val a15Match = HomeScaffoldFingerprint.instructionMatches[15]
        val vG = a15Match.getInstruction<OneRegisterInstruction>().registerA
        val appBarBodyInvoke = invokeStatic(
            vG,
            "$EXTENSION_CLASS->appBarBody($MODIFIER_INTERFACE)Ljava/lang/Object;"
        )

        val a9Match = HomeScaffoldFingerprint.instructionMatches[9]
        val a9Instruction = a9Match.instruction
        val a9MethodRef = a9Match.methodReference
        val a9FiveReg = a9Instruction as? FiveRegisterInstruction
        val a9RangeReg = a9Instruction as? RegisterRangeInstruction
        val (colourReg, origBgSmali) = when {
            a9FiveReg != null -> {
                val vM = a9FiveReg.registerC
                val vC = a9FiveReg.registerD
                val vC1 = a9FiveReg.registerE
                val vS = a9FiveReg.registerF
                vC to "invoke-static { v$vM, v$vC, v$vC1, v$vS }, $a9MethodRef"
            }

            a9RangeReg != null -> {
                val vM = a9RangeReg.startRegister
                val vC = vM + 1
                val vS = vM + 3
                vC to "invoke-static/range { v$vM .. v$vS }, $a9MethodRef"
            }

            else -> throw PatchException("Background call at A9 is neither FiveRegister nor RegisterRange")
        }
        val appBarColorInvoke = invokeStatic(
            colourReg,
            "$EXTENSION_CLASS->appBarColor(J)J",
            isWide = true
        )

        val a7Match = HomeScaffoldFingerprint.instructionMatches[7]
        val a7Instruction = a7Match.getInstruction<FiveRegisterInstruction>()
        val vU = a7Instruction.registerC
        val a7ReturnType = a7Match.methodReference.returnType
        val a8Match = HomeScaffoldFingerprint.instructionMatches[8]
        val vP = a8Match.getInstruction<OneRegisterInstruction>().registerA
        val feedBoxInvoke = invokeFeedBoxModifier(vP, vU)

        val a1Match = HomeScaffoldFingerprint.instructionMatches[1]
        val vZ = a1Match.getInstruction<OneRegisterInstruction>().registerA
        val sideLayoutInvoke = invokeStatic(
            vZ,
            "$EXTENSION_CLASS->sideLayout(Z)V"
        )

        val scaffoldMethod = HomeScaffoldFingerprint.method
        scaffoldMethod.addInstructions(
            a15Match.index + 1,
            """
                $appBarBodyInvoke
                move-result-object v$vG
                check-cast v$vG, $a14ReturnType
            """
        )

        scaffoldMethod.replaceInstruction(
            a9Match.index,
            appBarColorInvoke
        )
        scaffoldMethod.addInstructions(
            a9Match.index + 1,
            """
                move-result-wide v$colourReg
                $origBgSmali
            """
        )

        scaffoldMethod.addInstructions(
            a8Match.index + 1,
            """
                $feedBoxInvoke
                move-result-object v$vP
                check-cast v$vP, $a7ReturnType
            """
        )

        scaffoldMethod.addInstructions(
            a1Match.index + 1,
            sideLayoutInvoke
        )

        val b1Match = HomeTopBarHeightFingerprint.instructionMatches[1]
        val retReg = b1Match.getInstruction<OneRegisterInstruction>().registerA
        val topBarHeightInvoke = invokeStatic(
            retReg,
            "$EXTENSION_CLASS->topBarHeight(F)F"
        )
        HomeTopBarHeightFingerprint.method.addInstructions(
            b1Match.index,
            """
                $topBarHeightInvoke
                move-result v$retReg
            """
        )

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}

private fun invokeFeedBoxModifier(vP: Int, vU: Int): String = if (vP > 15 || vU > 15) {
    if (vU != vP + 1) {
        throw PatchException("Registers v$vP and v$vU cannot be encoded for feedBoxModifier")
    }
    "invoke-static/range { v$vP .. v$vU }, $EXTENSION_CLASS->feedBoxModifier($MODIFIER_INTERFACE$MODIFIER_INTERFACE)Ljava/lang/Object;"
} else {
    "invoke-static { v$vP, v$vU }, $EXTENSION_CLASS->feedBoxModifier($MODIFIER_INTERFACE$MODIFIER_INTERFACE)Ljava/lang/Object;"
}

private fun invokeStatic(register: Int, method: Any, isWide: Boolean = false): String =
    if (register > 15 || (isWide && register + 1 > 15)) {
        val endRegister = if (isWide) register + 1 else register
        "invoke-static/range { v$register .. v$endRegister }, $method"
    } else {
        val registers = if (isWide) "v$register, v${register + 1}" else "v$register"
        "invoke-static { $registers }, $method"
    }
