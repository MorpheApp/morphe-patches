/*
 * Copyright 2026 itolkien.
 * Adapted from https://github.com/itolkien/morphe-universal-captions
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.captions

import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.*
import com.android.tools.smali.dexlib2.iface.reference.*

internal data class CaptionTranslationAbi(
    val url: Reference,
    val label: Reference,
    val builder: ClassDef,
    val copy: Method,
    val languageSetter: Method,
    val urlSetter: Method,
    val vssSetter: Method,
    val labelField: FieldReference,
    val build: Method,
)

private fun Method.code() = implementation?.instructions?.toList().orEmpty()
private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference
private fun <T> Iterable<T>.unique(role: String): T = toList().let {
    check(it.size == 1) { "Caption translations: $role expected once, found ${it.size}: $it" }
    it.single()
}

/** Trace the register supplying an append/copy, allowing unrelated instructions and register moves. */
private fun readBefore(code: List<Instruction>, end: Int, register: Int): Reference? {
    var source = register
    for (i in end - 1 downTo 0) {
        val instruction = code[i]
        if (!instruction.opcode.canContinue()) return null
        if (!instruction.opcode.setsRegister() || (instruction as? OneRegisterInstruction)?.registerA != source) continue
        when (instruction.opcode) {
            Opcode.IGET_OBJECT -> return instruction.reference() as? FieldReference
            Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16 ->
                source = (instruction as TwoRegisterInstruction).registerB
            Opcode.MOVE_RESULT_OBJECT -> {
                val previous = code.getOrNull(i - 1)
                val method = previous?.reference() as? MethodReference ?: return null
                if (method.parameterTypes.isNotEmpty() || previous.opcode !in setOf(
                        Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE,
                        Opcode.INVOKE_INTERFACE, Opcode.INVOKE_INTERFACE_RANGE)) return null
                return method
            }
            else -> return null
        }
    }
    return null
}

private fun Reference.belongsTo(owner: String, type: String) = when (this) {
    is FieldReference -> definingClass == owner && this.type == type
    is MethodReference -> definingClass == owner && parameterTypes.isEmpty() && returnType == type
    else -> false
}

/** Resolve everything before mutation. Ambiguous models are rejected instead of guessing field positions. */
internal fun resolveCaptionTranslationAbi(
    track: ClassDef,
    translated: Method,
    language: Reference,
    vss: Reference,
    classBy: (String) -> ClassDef?,
): CaptionTranslationAbi {
    val string = "Ljava/lang/String;"
    val labelType = "Ljava/lang/CharSequence;"
    check(language.belongsTo(track.type, string) && vss.belongsTo(track.type, string))
    val listCode = translated.code()
    val anchor = listCode.indices.filter {
        (listCode[it].reference() as? StringReference)?.string == "&tlang="
    }.unique("translation URL anchor")
    val appendIndex = (0 until anchor).lastOrNull {
        val reference = listCode[it].reference() as? MethodReference
        reference?.definingClass == "Ljava/lang/StringBuilder;" && reference.name == "append" &&
                reference.parameterTypes.toList() == listOf(string)
    } ?: error("Caption translations: URL append not found")
    val append = listCode[appendIndex]
    val urlRegister = when (append) {
        is FiveRegisterInstruction -> append.registerD
        is RegisterRangeInstruction -> append.startRegister + 1
        else -> error("Caption translations: unsupported URL append")
    }
    val url = readBefore(listCode, appendIndex, urlRegister)
        ?.takeIf { it.belongsTo(track.type, string) }
        ?: error("Caption translations: URL is not a track field or getter")
    val label = track.methods.filter { it.name == "toString" && it.parameterTypes.isEmpty() }
        .flatMap { it.code() }.mapNotNull { it.reference() }
        .filter { it.belongsTo(track.type, labelType) }.distinct().unique("display accessor")
    val builders = track.methods.filter {
        AccessFlags.STATIC.isSet(it.accessFlags) && it.parameterTypes.isEmpty()
    }.mapNotNull { classBy(it.returnType) }.distinctBy { it.type }.filter { builder ->
        builder.methods.any { it.name == "<init>" && it.parameterTypes.toList() == listOf(track.type) }
    }
    val builder = builders.unique("copy builder")
    val copy = builder.methods.filter {
        it.name == "<init>" && it.parameterTypes.toList() == listOf(track.type) &&
                AccessFlags.PUBLIC.isSet(it.accessFlags)
    }.unique("public copy constructor")
    val copyCode = copy.code()
    val copyThis = copy.implementation!!.registerCount - 2
    fun builderField(accessor: Reference): FieldReference = copyCode.mapIndexedNotNull { index, instruction ->
        val field = instruction.reference() as? FieldReference
        if (instruction.opcode != Opcode.IPUT_OBJECT || field?.definingClass != builder.type) return@mapIndexedNotNull null
        val write = instruction as TwoRegisterInstruction
        if (write.registerB != copyThis || readBefore(copyCode, index, write.registerA) != accessor) null else field
    }.distinct().unique("copy of $accessor")

    fun setter(accessor: Reference, role: String): Method {
        val field = builderField(accessor)
        return builder.methods.filter { method ->
            if (method.parameterTypes.toList() != listOf(string) || method.returnType != "V" ||
                !AccessFlags.PUBLIC.isSet(method.accessFlags) || AccessFlags.STATIC.isSet(method.accessFlags)) return@filter false
            val count = method.implementation?.registerCount ?: return@filter false
            method.code().any { instruction ->
                instruction.opcode == Opcode.IPUT_OBJECT && instruction.reference() == field &&
                        (instruction as TwoRegisterInstruction).registerA == count - 1 && instruction.registerB == count - 2
            }
        }.unique("$role setter")
    }
    val build = builder.methods.filter {
        it.parameterTypes.isEmpty() && it.returnType == track.type &&
                AccessFlags.PUBLIC.isSet(it.accessFlags) && !AccessFlags.STATIC.isSet(it.accessFlags)
    }.unique("build track")
    return CaptionTranslationAbi(url, label, builder, copy, setter(language, "language"),
        setter(url, "URL"), setter(vss, "vss"), builderField(label), build)
}
