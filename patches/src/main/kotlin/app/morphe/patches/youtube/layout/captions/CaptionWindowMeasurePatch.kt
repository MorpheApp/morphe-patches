/*
 * Copyright 2026 Morphe.
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.captions

import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction22t
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.*
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal data class CaptionHeightModeCorrection(
    val instructionIndex: Int,
    val registerA: Int,
    val registerB: Int,
)

/** Resolve the height EXACTLY branch from the two MeasureSpec.getMode calls. */
internal fun findCaptionHeightModeCorrection(method: Method): CaptionHeightModeCorrection? {
    check(
        method.name == "onMeasure" &&
            method.parameterTypes == listOf("I", "I") &&
            method.returnType == "V",
    )
    val body = checkNotNull(method.implementation)
    val instructions = body.instructions.toList()
    fun modeCall(instruction: Instruction): Boolean {
        val ref =
            (instruction as? ReferenceInstruction)?.reference as? MethodReference ?: return false
        return instruction.opcode in setOf(Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE) &&
            ref.definingClass == "Landroid/view/View\$MeasureSpec;" &&
            ref.name == "getMode" &&
            ref.parameterTypes == listOf("I") &&
            ref.returnType == "I"
    }
    fun argument(instruction: Instruction): Int =
        when (instruction) {
            is FiveRegisterInstruction -> {
                check(instruction.registerCount == 1)
                instruction.registerC
            }
            is RegisterRangeInstruction -> {
                check(instruction.registerCount == 1)
                instruction.startRegister
            }
            else -> error("Unknown caption measurement call")
        }
    fun result(index: Int): Int {
        check(index + 1 < instructions.size) { "Incomplete caption mode result" }
        val move = instructions[index + 1]
        check(move.opcode == Opcode.MOVE_RESULT)
        return (move as OneRegisterInstruction).registerA
    }
    val calls = instructions.indices.filter { modeCall(instructions[it]) }
    check(calls.size == 2) { "Expected separate caption width/height mode calls" }
    val (widthCall, heightCall) = calls
    check(argument(instructions[widthCall]) == body.registerCount - 2)
    check(argument(instructions[heightCall]) == body.registerCount - 1)
    val widthMode = result(widthCall)
    val heightMode = result(heightCall)
    check(widthMode != heightMode)
    val exact =
        (widthCall + 2 until heightCall)
            .mapNotNull { index ->
                val literal = instructions[index] as? NarrowLiteralInstruction
                if (literal?.narrowLiteral == 0x40000000)
                    (instructions[index] as? OneRegisterInstruction)?.registerA
                else null
            }
            .distinct()
            .single()
    check(exact != widthMode && exact != heightMode)
    val atMost =
        instructions
            .take(widthCall)
            .mapNotNull { instruction ->
                val literal = instruction as? NarrowLiteralInstruction
                if (literal?.narrowLiteral == Int.MIN_VALUE)
                    (instruction as? OneRegisterInstruction)?.registerA
                else null
            }
            .distinct()
            .single()
    check(heightCall + 2 < instructions.size) { "Incomplete caption height branch" }
    val limitBranch = instructions[heightCall + 2]
    check(limitBranch.opcode == Opcode.IF_NE)
    val limitRegisters = limitBranch as TwoRegisterInstruction
    check(setOf(limitRegisters.registerA, limitRegisters.registerB) == setOf(heightMode, atMost))
    val candidates =
        (heightCall + 3 until instructions.size).filter { index ->
            val instruction = instructions[index]
            val r = instruction as? TwoRegisterInstruction
            instruction.opcode in setOf(Opcode.IF_NE, Opcode.IF_EQ) &&
                r != null &&
                ((r.registerA == exact && r.registerB in setOf(widthMode, heightMode)) ||
                    (r.registerB == exact && r.registerA in setOf(widthMode, heightMode)))
        }
    check(candidates.size == 1) { "Ambiguous caption height EXACTLY branch" }
    val index = candidates.single()
    val offsets =
        instructions.runningFold(0) { offset, instruction -> offset + instruction.codeUnits }
    check(offsets[heightCall + 2] + (limitBranch as OffsetInstruction).codeOffset == offsets[index])
    val branch = instructions[index] as TwoRegisterInstruction
    if (branch.registerA == heightMode || branch.registerB == heightMode) return null
    return CaptionHeightModeCorrection(
        index,
        if (branch.registerA == widthMode) heightMode else branch.registerA,
        if (branch.registerB == widthMode) heightMode else branch.registerB,
    )
}

/** Correct the height-mode operand only when its native control-flow shape matches. */
internal val captionWindowMeasurePatch =
    bytecodePatch(
        description = "Corrects the native caption window height measurement mode.",
    ) {
        execute {
            val support =
                optionalCaptionSupport("caption height measurement") {
                    val window =
                        getCaptionWindowSetterFingerprint(resolveCaptionCueType()).classDefOrNull
                            ?: error("Caption window not found")
                    val method =
                        window.methods.single {
                            it.name == "onMeasure" && it.parameterTypes == listOf("I", "I")
                        }
                    method to findCaptionHeightModeCorrection(method)
                } ?: return@execute
            val (method, correction) = support
            if (correction == null) return@execute
            val branch =
                method.implementation!!.instructions.toList()[correction.instructionIndex]
                    as BuilderInstruction22t
            method.replaceInstruction(
                correction.instructionIndex,
                BuilderInstruction22t(
                    branch.opcode,
                    correction.registerA,
                    correction.registerB,
                    branch.target,
                ),
            )
        }
    }
