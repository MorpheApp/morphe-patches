/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.glassnav

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.registersUsed
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.misc.version.is_2026_44_0_or_greater
import app.morphe.patches.reddit.misc.version.is_2026_47_0_or_greater
import app.morphe.patches.reddit.misc.version.versionCheckPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.setExtensionIsPatchIncluded
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import java.util.logging.Logger

@Suppress("unused")
val glassNavigationBarPatch = patch(
    name = "Glass navigation bar",
    description = "Adds an option to turn the bottom navigation bar, or the side rail on tablets, into a floating see-through capsule. " +
        "This patch works with Reddit 2026.44.0 and newer."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(settingsPatch, versionCheckPatch)

    execute {
        if (!is_2026_44_0_or_greater) {
            return@execute Logger.getLogger(this::class.java.name).warning(
                "'Glass navigation bar' requires Reddit 2026.44.0+"
            )
        }

        val backdropFingerprint = if (is_2026_47_0_or_greater) {
            BottomNavBackdropFingerprint
        } else {
            BottomNavBackdropLegacyFingerprint
        }

        val a2Match = BottomNavBarFingerprint.instructionMatches[2]
        val a2Instruction = a2Match.getInstruction<RegisterRangeInstruction>()
        val s = a2Instruction.startRegister
        val a2Param0 = a2Match.methodReference.parameterTypes[0]

        val barModifierInvoke = invokeStatic(
            s,
            "$EXTENSION_CLASS->barModifier(Ljava/lang/Object;)Ljava/lang/Object;"
        )
        val elevationReg = s + 2
        val barElevationInvoke = invokeStatic(
            elevationReg,
            "$EXTENSION_CLASS->barElevation(F)F"
        )
        val colorReg = s + 3
        val barColorInvoke = invokeStatic(
            colorReg,
            "$EXTENSION_CLASS->barColor(J)J",
            isWide = true
        )

        BottomNavBarFingerprint.method.addInstructions(
            a2Match.index,
            """
                $barModifierInvoke
                move-result-object v$s
                check-cast v$s, $a2Param0
                $barElevationInvoke
                move-result v$elevationReg
                $barColorInvoke
                move-result-wide v$colorReg
            """
        )

        val a0Match = BottomNavBarFingerprint.instructionMatches[0]
        val func1Reg = a0Match.instruction.registersUsed[1]
        val barDrawInvoke = invokeStatic(
            func1Reg,
            "$EXTENSION_CLASS->barDraw(Lkotlin/jvm/functions/Function1;)Lkotlin/jvm/functions/Function1;"
        )

        BottomNavBarFingerprint.method.addInstructions(
            a0Match.index,
            """
                $barDrawInvoke
                move-result-object v$func1Reg
            """
        )

        val f5Match = backdropFingerprint.instructionMatches[5]
        val f5Instruction = f5Match.getInstruction<FiveRegisterInstruction>()
        val regD = f5Instruction.registerD
        val backdropInvoke = invokeStatic(
            regD,
            "$EXTENSION_CLASS->barBackdrop(J)J",
            isWide = true
        )

        backdropFingerprint.method.addInstructions(
            f5Match.index,
            """
                $backdropInvoke
                move-result-wide v$regD
            """
        )

        val b3Index = SideNavRailFingerprint.instructionMatches[3].index
        val vM = SideNavRailFingerprint.instructionMatches[2].getInstruction<OneRegisterInstruction>().registerA
        val vT = SideNavRailFingerprint.instructionMatches[3].getInstruction<OneRegisterInstruction>().registerA
        val b = SideNavRailFingerprint.instructionMatches[4].getInstruction<RegisterRangeInstruction>().startRegister

        val a0MethodRef = BottomNavBarFingerprint.instructionMatches[0].methodReference
        val f0FieldRef = backdropFingerprint.instructionMatches[0].fieldReference
        val f1MethodRef = backdropFingerprint.instructionMatches[1].methodReference
        val f2Type = (backdropFingerprint.instructionMatches[2].instruction as ReferenceInstruction).reference
        val f3FieldRef = backdropFingerprint.instructionMatches[3].fieldReference
        val f4MethodRef = backdropFingerprint.instructionMatches[4].methodReference

        if (vM > 15 || vT > 15) {
            throw PatchException("Unexpected side rail registers")
        }
        val drawWithContentInvoke = "invoke-static { v$vM, v$vT }, $a0MethodRef"

        val themeColorInvoke = invokeStatic(
            b + 2,
            "$EXTENSION_CLASS->railThemeColor(J)V",
            isWide = true
        )

        SideNavRailFingerprint.method.addInstructions(
            b3Index,
            """
                invoke-static { p1 }, $EXTENSION_CLASS->railTabs(Ljava/lang/Object;)V
                invoke-static { }, $EXTENSION_CLASS->railDraw()Lkotlin/jvm/functions/Function1;
                move-result-object v$vT
                $drawWithContentInvoke
                move-result-object v$vM
                sget-object v${b + 1}, $f0FieldRef
                invoke-virtual { v${b + 4}, v${b + 1} }, $f1MethodRef
                move-result-object v${b + 1}
                check-cast v${b + 1}, $f2Type
                iget-object v${b + 1}, v${b + 1}, $f3FieldRef
                invoke-virtual { v${b + 1} }, $f4MethodRef
                move-result-wide v${b + 2}
                $themeColorInvoke
            """
        )

        val g4Match = RailStripColorFingerprint.instructionMatches[4]
        val stripColorRef = g4Match.methodReference
        val vC = g4Match.instruction.registersUsed[0]
        val pageColorInvoke = invokeStatic(
            vC,
            "$EXTENSION_CLASS->pageColor(Ljava/lang/Object;)J"
        )

        RailStripColorFingerprint.method.replaceInstruction(
            g4Match.index,
            pageColorInvoke
        )

        if (is_2026_47_0_or_greater) {
            NavScreenContentStateConstructorFingerprint.method.addInstructions(
                0,
                """
                    invoke-static { p2 }, $EXTENSION_CLASS->padContentForBottomNav(Z)Z
                    move-result p2
                """
            )
        }

        val drawRoundRectMethod = DrawRoundRectFingerprint.method
        val drawScopeClass = drawRoundRectMethod.parameterTypes[0]
        val drawStyleClass = drawRoundRectMethod.parameterTypes[5]
        DrawRoundRectStubFingerprint.method.addInstructions(
            0,
            """
                check-cast p0, $drawScopeClass
                check-cast p9, $drawStyleClass
                invoke-static/range { p0 .. p11 }, $drawRoundRectMethod
                return-void
            """
        )

        val drawScopeSizeRef = DrawRoundRectFingerprint.instructionMatches[0].methodReference
        val drawScopeSizeClass = drawScopeSizeRef.definingClass
        DrawScopeSizeStubFingerprint.method.addInstructions(
            0,
            """
                check-cast p0, $drawScopeSizeClass
                invoke-interface { p0 }, $drawScopeSizeRef
                move-result-wide v0
                return-wide v0
            """
        )

        val drawContentMethod = DrawContentFingerprint.method
        val drawContentClass = drawContentMethod.definingClass
        DrawContentStubFingerprint.method.addInstructions(
            0,
            """
                check-cast p0, $drawContentClass
                invoke-virtual { p0 }, $drawContentMethod
                return-void
            """
        )

        val paddingMethod = PaddingFingerprint.method
        val modifierClass = paddingMethod.parameterTypes[0]
        PaddingStubFingerprint.method.addInstructions(
            0,
            """
                check-cast p0, $modifierClass
                invoke-static { p0, p1, p2, p3, p4 }, $paddingMethod
                move-result-object p0
                return-object p0
            """
        )

        val colorsFieldType = RailStripColorFingerprint.instructionMatches[3].fieldReference.type
        val feedColorRef = backdropFingerprint.instructionMatches[4].methodReference
        FeedColorStubFingerprint.method.addInstructions(
            0,
            """
                check-cast p0, $colorsFieldType
                invoke-virtual { p0 }, $feedColorRef
                move-result-wide v0
                return-wide v0
            """
        )

        StripColorStubFingerprint.method.addInstructions(
            0,
            """
                check-cast p0, $colorsFieldType
                invoke-virtual { p0 }, $stripColorRef
                move-result-wide v0
                return-wide v0
            """
        )

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}

private fun invokeStatic(register: Int, method: Any, isWide: Boolean = false): String = if (register > 15) {
    val endRegister = if (isWide) register + 1 else register
    "invoke-static/range { v$register .. v$endRegister }, $method"
} else {
    val registers = if (isWide) "v$register, v${register + 1}" else "v$register"
    "invoke-static { $registers }, $method"
}
