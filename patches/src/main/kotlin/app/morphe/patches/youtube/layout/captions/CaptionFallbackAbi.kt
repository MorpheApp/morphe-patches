/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3891
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.captions

import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.MethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x

/** Extract the complete straight-line native option factory, including its localized label/flags.
 * Keep its register layout: the original method may overwrite its own p0 before returning.
 * All validation completes before modifying either original entry point.
 */
internal fun resolveNativeAutoOption(model: CaptionTranslationModel, abi: CaptionTranslationAbi): MethodImplementation {
    val implementation = model.direct.implementation ?: error("Missing direct menu implementation")
    check(implementation.tryBlocks.isEmpty()) { "Unexpected native menu exception regions" }
    val code = implementation.instructions.toList()
    val anchor = code.indices.single {
        ((code[it] as? ReferenceInstruction)?.reference as? StringReference)?.string == "AUTO_TRANSLATE_CAPTIONS_OPTION"
    }
    val gate = (0 until anchor).last { code[it] is OffsetInstruction }
    check(code[gate].opcode in setOf(Opcode.IF_EQZ, Opcode.IF_NEZ, Opcode.IF_GTZ, Opcode.IF_LEZ)) {
        "Unexpected native auto-option gate"
    }
    val build = (anchor + 1 until code.size).first { index ->
        val ref = (code[index] as? ReferenceInstruction)?.reference as? MethodReference
        ref?.definingClass == abi.build.definingClass && ref.name == abi.build.name &&
                ref.parameterTypes.isEmpty() && ref.returnType == model.track.type
    }
    check(code[build + 1].opcode == Opcode.MOVE_RESULT_OBJECT)
    val block = code.subList(gate + 1, build + 2)
    check(block.none { it is OffsetInstruction || !it.opcode.canContinue() }) {
        "Native auto-option factory must be straight-line"
    }
    val result = (code[build + 1] as OneRegisterInstruction).registerA
    // No branches or try blocks are copied, so no external labels can enter the helper.
    return ImmutableMethodImplementation(implementation.registerCount,
        block + ImmutableInstruction11x(Opcode.RETURN_OBJECT, result), emptyList(), emptyList())
}
