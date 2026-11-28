/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.shared.misc.proto

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.checkCast
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal object NewElementProtoParserFingerprint : Fingerprint(
    classFingerprint = ProtoStuffReflectionFingerprint,
    isStatic = true,
    parameters = listOf("L"),
    returnType = "[B",
    filters = listOf(
        checkCast("[B")
    )
)

private object ProtoStuffReflectionFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.STATIC),
    parameters = listOf(),
    returnType = "Ljava/lang/reflect/Field;",
    filters = listOf(
        string("buf"),
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            name = "getDeclaredField"
        ),
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            name = "setAccessible"
        )
    )
)

/**
 * Bytes of an inline resource of a response, such as a template or a JavaScript module.
 * When a server flag is on, the bytes are read with the element proto parser.
 * Added in YouTube 21.40.
 */
internal fun resourceBytesFingerprint(elementProtoParser: MethodReference) = Fingerprint(
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.STATIC),
    returnType = "[B",
    parameters = listOf(elementProtoParser.parameterTypes.first().toString(), "Z"),
    filters = listOf(
        methodCall(reference = elementProtoParser),
        opcode(Opcode.ARRAY_LENGTH)
    )
)

/**
 * Checks if an element is rendered with RenderNext, from the bytes of the element.
 */
internal object RenderNextEnablementCheckFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "Z",
    filters = listOf(
        string("Failed to read Element proto passed in to RenderNextEnablementCheck: ")
    )
)

/**
 * Converts an element. The bytes of the element are parsed for the RenderNext check,
 * and the converted element parses them again.
 */
internal fun elementConverterFingerprint(renderNextEnablementCheck: MethodReference) = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "L",
    parameters = listOf("L"),
    filters = listOf(
        methodCall(returnType = "[B"),
        methodCall(reference = renderNextEnablementCheck)
    )
)
