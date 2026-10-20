/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3566
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.captions

import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

internal data class CaptionMetadataAbi(
    val metadata: FieldReference,
    val targets: FieldReference,
    val targetCode: FieldReference,
    val defaultSource: Method,
)

/** Follow the target-language builder argument back to its protobuf entry and outer list. */
internal fun resolveCaptionMetadataAbi(model: CaptionTranslationModel, abi: CaptionTranslationAbi,
                                      manager: ClassDef): CaptionMetadataAbi {
    val code = model.translated.implementation!!.instructions.toList()
    fun Instruction.ref() = (this as? ReferenceInstruction)?.reference
    fun <T> List<T>.one(role: String): T {
        check(size == 1) { "Caption metadata: $role expected once, found $size" }
        return single()
    }
    val setter = code.indices.filter { code[it].ref() == abi.languageSetter }.one("target language setter")
    val invoke = code[setter]
    val register = when (invoke) {
        is FiveRegisterInstruction -> invoke.registerD
        is RegisterRangeInstruction -> invoke.startRegister + 1
        else -> error("Caption metadata: unsupported language setter")
    }
    val fieldIndex = (setter - 1 downTo 0).firstOrNull {
        code[it].opcode == Opcode.IGET_OBJECT && (code[it] as TwoRegisterInstruction).registerA == register
    } ?: error("Caption metadata: target code read missing")
    val targetCode = code[fieldIndex].ref() as FieldReference
    check(targetCode.type == "Ljava/lang/String;" && targetCode.definingClass != model.track.type)
    val cast = (0 until fieldIndex).filter { code[it].opcode == Opcode.CHECK_CAST &&
            (code[it].ref() as? TypeReference)?.type == targetCode.definingClass }.one("target entry cast")
    val iterator = (cast - 1 downTo 0).firstOrNull {
        val ref = code[it].ref() as? MethodReference
        ref?.name == "iterator" && ref.returnType == "Ljava/util/Iterator;"
    } ?: error("Caption metadata: target iterator missing")
    val iteratorRegister = when (val instruction = code[iterator]) {
        is FiveRegisterInstruction -> instruction.registerC
        is RegisterRangeInstruction -> instruction.startRegister
        else -> error("Caption metadata: unsupported iterator")
    }
    val listIndex = (iterator - 1 downTo 0).firstOrNull { code[it].opcode == Opcode.IGET_OBJECT &&
            (code[it] as TwoRegisterInstruction).registerA == iteratorRegister }
        ?: error("Caption metadata: target list missing")
    val targets = code[listIndex].ref() as FieldReference
    val metadata = manager.fields.filter { it.type == targets.definingClass && !AccessFlags.STATIC.isSet(it.accessFlags) }
        .map { it as FieldReference }.one("manager metadata field")
    val defaultSource = manager.methods.filter { it.returnType == model.track.type && it.parameterTypes.isEmpty() &&
            !AccessFlags.STATIC.isSet(it.accessFlags) && AccessFlags.PUBLIC.isSet(it.accessFlags) }.one("native default source")

    check(code.take(listIndex).any { it.opcode == Opcode.IGET_OBJECT && it.ref() == metadata })

    // Native target names are rich-text protobuf messages, not direct String fields.
    // Resolve their renderer from verified DEX before injecting a runtime accessor.
    return CaptionMetadataAbi(metadata, targets, targetCode, defaultSource)
}

/** The original Android caption manager's general source fallback, scoped to current audio. */
internal data class CaptionEligibleSourceAbi(
    val audio: FieldReference,
    val audioCaptionIndices: FieldReference,
    val generalSourceIndices: FieldReference,
    val originals: FieldReference,
    val convertOriginal: Method,
)

/** Resolve existing *native* source routing, not obfuscated class or member names.
 * All five relationships must be unique in both native list getters.
 * Unknown layouts fail closed; the already working translation hooks remain usable.
 */
internal fun resolveCaptionEligibleSourceAbi(
    model: CaptionTranslationModel, metadata: CaptionMetadataAbi, manager: ClassDef
): CaptionEligibleSourceAbi? {
    val translated = model.translated.implementation?.instructions?.toList() ?: return null
    val direct = model.direct.implementation?.instructions?.toList() ?: return null
    fun Instruction.ref() = (this as? ReferenceInstruction)?.reference
    fun List<Instruction>.references() = mapNotNull { it.ref() }.toSet()
    val translatedRefs = translated.references()
    val directRefs = direct.references()

    val converters = manager.methods.filter { method ->
        method.returnType == model.track.type && method.parameterTypes.size == 1 &&
                AccessFlags.PRIVATE.isSet(method.accessFlags) &&
                !AccessFlags.STATIC.isSet(method.accessFlags) &&
                method in translatedRefs && method in directRefs
    }
    val convert = converters.singleOrNull() ?: return null
    val originalType = convert.parameterTypes.single().toString()

    val audio = manager.fields.map { it as FieldReference }.filter { field ->
        field.type.startsWith("L") &&
                field.type != "Ljava/lang/String;" &&
                field != metadata.metadata &&
                field in translatedRefs && field in directRefs
    }.singleOrNull() ?: return null

    val audioCaptionIndices = translatedRefs.filterIsInstance<FieldReference>().filter {
        it.definingClass == audio.type && it in directRefs
    }.distinct().singleOrNull() ?: return null

    // The general source index list and the current audio caption index list
    // use the same protobuf repeated-int implementation on this native model.
    val general = translatedRefs.filterIsInstance<FieldReference>().filter {
        it.definingClass == metadata.targets.definingClass &&
                it.type == audioCaptionIndices.type && it in directRefs
    }.distinct().singleOrNull() ?: return null

    fun sourceReadFeedsConverter(code: List<Instruction>, index: Int): Boolean {
        val tail = code.drop(index + 1).take(8)
        val get = tail.indexOfFirst {
            val ref = it.ref() as? MethodReference
            ref?.name == "get" && ref.parameterTypes.toList() == listOf("I") &&
                    ref.returnType == "Ljava/lang/Object;"
        }
        if (get !in 0..3) return false
        val cast = ((get + 1) until tail.size).firstOrNull {
            tail[it].opcode == Opcode.CHECK_CAST &&
                    (tail[it].ref() as? TypeReference)?.type == originalType
        } ?: return false
        return cast <= get + 3 &&
                ((cast + 1) until minOf(tail.size, cast + 4)).any { tail[it].ref() == convert }
    }
    val originals = translated.indices.mapNotNull { index ->
        val field = translated[index].ref() as? FieldReference ?: return@mapNotNull null
        if (translated[index].opcode != Opcode.IGET_OBJECT ||
                field.definingClass != metadata.targets.definingClass ||
                field.type != metadata.targets.type ||
                !sourceReadFeedsConverter(translated, index) ||
                field !in directRefs) null else field
    }.distinct().singleOrNull() ?: return null

    check(originals != metadata.targets && general != originals)
    return CaptionEligibleSourceAbi(audio, audioCaptionIndices, general, originals, convert)
}
