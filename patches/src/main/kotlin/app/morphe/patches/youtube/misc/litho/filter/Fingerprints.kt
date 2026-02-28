/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 */

package app.morphe.patches.youtube.misc.litho.filter

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.OpcodesFilter
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal object AccessibilityIdFingerprint : Fingerprint(
    filters = listOf(
        methodCall(
            opcode = Opcode.INVOKE_INTERFACE,
            parameters = listOf(),
            returnType = "Ljava/lang/String;"
        ),
        string("primary_image", location = MatchAfterWithin(5)),
    )
)

/**
 * Resolves using the method found in [EmptyComponentParentFingerprint].
 */
internal object EmptyComponentFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "L",
    parameters = listOf("L")
)

internal object EmptyComponentParentFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.CONSTRUCTOR),
    parameters = listOf(),
    filters = listOf(
        string("EmptyComponent")
    )
)

internal object LithoFilterFingerprint : Fingerprint(
    definingClass = EXTENSION_CLASS_DESCRIPTOR,
    accessFlags = listOf(AccessFlags.STATIC, AccessFlags.CONSTRUCTOR),
    filters = listOf(
        fieldAccess(
            opcode = Opcode.SPUT_OBJECT,
            definingClass = "this",
            type = EXTENSION_FILER_ARRAY_DESCRIPTOR
        )
    )
)

internal object ProtobufBufferEncodeFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "[B",
    parameters = listOf(),
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            definingClass = "this",
            type = "Lcom/google/android/libraries/elements/adl/UpbMessage;"
        ),
        methodCall(
            definingClass = "Lcom/google/android/libraries/elements/adl/UpbMessage;",
            name = "jniEncode"
        )
    )
)

internal object ProtobufBufferReferenceFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("I", "Ljava/nio/ByteBuffer;"),
    filters = OpcodesFilter.opcodesToFilters(
        Opcode.IPUT,
        Opcode.INVOKE_VIRTUAL,
        Opcode.MOVE_RESULT,
        Opcode.SUB_INT_2ADDR,
    )
)

internal object LithoThreadExecutorFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    parameters = listOf("I", "I", "I"),
    filters = listOf(
        literal(1L) // 1L = default thread timeout.
    ),
    custom = { _, classDef ->
        classDef.superclass == "Ljava/util/concurrent/ThreadPoolExecutor;"
    }
)

internal object LithoComponentNameUpbFeatureFlagFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Z",
    parameters = listOf(),
    filters = listOf(
        literal(45631264L)
    )
)

internal object LithoConverterBufferUpbFeatureFlagFingerprint : Fingerprint(
    returnType = "L",
    filters = listOf(
        literal(45419603L)
    )
)
