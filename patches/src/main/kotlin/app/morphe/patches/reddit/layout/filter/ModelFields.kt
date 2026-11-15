/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.filter

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.*
import com.android.tools.smali.dexlib2.iface.reference.*

/**
 * R8 outlines the StringBuilder into static helpers in this Reddit release. Follow those
 * helpers symbolically at patch time so field roles come from labels, not obfuscated names.
 * This only reads DEX: no app code executes and no reflection is used by the extension.
 */
internal fun modelFields(method: Method): Map<String, FieldReference> {
    class Builder(val tokens: MutableList<Any> = mutableListOf())
    fun interpret(method: Method, arguments: List<Any?>, depth: Int): Any? {
        check(depth < 8) { "Too many outlined diagnostic helpers: $method" }
        val implementation = checkNotNull(method.implementation)
        val registers = arrayOfNulls<Any>(implementation.registerCount)
        val parameterWords = method.parameterTypes.sumOf { if (it == "J" || it == "D") 2 else 1 } +
            if (method.accessFlags and 8 == 0) 1 else 0
        var register = registers.size - parameterWords
        arguments.forEachIndexed { index, value ->
            registers[register] = value
            val type = if (method.accessFlags and 8 == 0) method.parameterTypes.getOrNull(index - 1) else method.parameterTypes.getOrNull(index)
            register += if (type == "J" || type == "D") 2 else 1
        }
        var result: Any? = null
        for (instruction in implementation.instructions) {
            val opcode = instruction.opcode.toString()
            when {
                opcode.startsWith("CONST_STRING") -> registers[(instruction as OneRegisterInstruction).registerA] =
                    ((instruction as ReferenceInstruction).reference as StringReference).string
                opcode.startsWith("IGET") -> registers[(instruction as TwoRegisterInstruction).registerA] =
                    (instruction as ReferenceInstruction).reference
                opcode == "NEW_INSTANCE" -> registers[(instruction as OneRegisterInstruction).registerA] =
                    if (((instruction as ReferenceInstruction).reference as TypeReference).type == "Ljava/lang/StringBuilder;") Builder() else null
                opcode.startsWith("MOVE_RESULT") -> registers[(instruction as OneRegisterInstruction).registerA] = result
                opcode.startsWith("MOVE_OBJECT") -> {
                    instruction as TwoRegisterInstruction
                    registers[instruction.registerA] = registers[instruction.registerB]
                }
                opcode.startsWith("CONST") -> registers[(instruction as OneRegisterInstruction).registerA] = null
                opcode.startsWith("INVOKE_") -> {
                    val ref = (instruction as ReferenceInstruction).reference as? MethodReference ?: continue
                    val indices = when (instruction) {
                        is RegisterRangeInstruction -> (instruction.startRegister until instruction.startRegister + instruction.registerCount).toList()
                        is FiveRegisterInstruction -> listOf(instruction.registerC, instruction.registerD, instruction.registerE,
                            instruction.registerF, instruction.registerG).take(instruction.registerCount)
                        else -> error("Unsupported invoke: $instruction")
                    }
                    val values = indices.map { registers[it] }
                    if (ref.definingClass == "Ljava/lang/StringBuilder;") {
                        val builder = values.firstOrNull() as? Builder
                        when (ref.name) {
                            "append", "<init>" -> values.drop(1).filterNotNull().forEach { builder?.tokens?.add(it) }
                            "toString" -> Unit
                            else -> error("Unexpected StringBuilder operation: $ref")
                        }
                        result = builder
                    } else if (ref.returnType == "Ljava/lang/StringBuilder;" || values.any { it is Builder }) {
                        check(opcode.startsWith("INVOKE_STATIC")) { "Unexpected diagnostic helper: $ref" }
                        val helper = Fingerprint(definingClass = ref.definingClass, name = ref.name,
                            returnType = ref.returnType, parameters = ref.parameterTypes.map { it.toString() }).originalMethod
                        result = interpret(helper, values, depth + 1)
                    } else {
                        // Formatting a single field (e.g. a nullable timestamp) keeps its provenance.
                        result = values.filterIsInstance<FieldReference>().singleOrNull()
                    }
                }
                opcode == "RETURN_OBJECT" -> return registers[(instruction as OneRegisterInstruction).registerA]
            }
        }
        return null
    }
    val builder = interpret(method, listOf(null), 0) as? Builder
        ?: error("Missing diagnostic StringBuilder in $method")
    val tokens = builder.tokens
    return buildMap {
        tokens.forEachIndexed { index, token ->
            if (token is String && token.endsWith("=")) {
                val field = tokens.getOrNull(index + 1) as? FieldReference
                if (field != null) {
                    check(!containsKey(token)) { "Ambiguous field label $token in $method" }
                    put(token, field)
                }
            }
        }
    }
}
