/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.shared.misc.litho.node

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.newInstance
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private object ComponentContextParserFingerprint : Fingerprint(
    returnType = "L",
    filters = listOf(
        string("Failed to parse Element proto."),
        string("Cannot read theme key from model.")
    )
)

internal object TreeNodeResultListFingerprint : Fingerprint(
    classFingerprint = ComponentContextParserFingerprint,
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.FINAL),
    returnType = "Ljava/util/List;",
    filters = listOf(
        methodCall(name = "nCopies", opcode = Opcode.INVOKE_STATIC)
    )
)

internal object ComponentPatchFingerprint : Fingerprint(
    definingClass = EXTENSION_CLASS,
    name = "onComponentLoaded",
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.STATIC)
)

internal object LazilyConvertedElementPatchFingerprint : Fingerprint(
    definingClass = EXTENSION_CLASS,
    name = "onLazilyConvertedElementLoaded",
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.STATIC)
)

internal object TreeNodeListFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PROTECTED, AccessFlags.FINAL),
    returnType = "L",
    parameters = listOf("L"),
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            definingClass = "this",
            type = "L",
            location = MatchAfterWithin(5) // Match close to start of method.
        ),
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            type = "Ljava/util/concurrent/atomic/AtomicReference;",
            location = MatchAfterWithin(5)
        ),
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            type = "Ljava/util/concurrent/atomic/AtomicReference;",
            location = MatchAfterWithin(2)
        ),
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            smali = "Ljava/util/concurrent/atomic/AtomicReference;->get()Ljava/lang/Object;",
            location = MatchAfterWithin(2)
        )
    )
)

internal object TreeNodeListHelperParentFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "L",
    parameters = listOf("L", "L"),
    filters = listOf(
        newInstance("Ljava/util/ArrayList;"),
        methodCall(
            opcode = Opcode.INVOKE_DIRECT,
            smali = "Ljava/util/ArrayList;-><init>()V"
        ),
        methodCall(opcode = Opcode.INVOKE_VIRTUAL_RANGE),
        methodCall(
            opcode = Opcode.INVOKE_STATIC,
            returnType = "V",
            parameters = listOf("L", "L")
        )
    ),
    custom = custom@{ method, _ ->
        val instructions = method.implementation?.instructions ?: return@custom false
        val rangeInvoke = instructions.find { it.opcode == Opcode.INVOKE_VIRTUAL_RANGE }
                as? ReferenceInstruction ?: return@custom false
        val methodRef = rangeInvoke.reference as? MethodReference ?: return@custom false
        val params = methodRef.parameterTypes

        if (params.size != 8) return@custom false
        if (params[5].toString() != "I") return@custom false
        if (params[7].toString() != "Z") return@custom false

        true
    }
)

internal object TreeNodeListHelperConstructorFingerprint : Fingerprint(
    classFingerprint = TreeNodeListHelperParentFingerprint,
    name = "<init>",
    filters = listOf(
        fieldAccess(opcode = Opcode.IPUT_OBJECT)
    )
)
