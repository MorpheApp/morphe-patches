/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to Morphe contributions.
 */

package app.morphe.patches.shared.misc.litho

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.OpcodesFilter
import app.morphe.patcher.checkCast
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

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

private object EmptyComponentParentFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.CONSTRUCTOR),
    parameters = listOf(),
    filters = listOf(
        string("EmptyComponent")
    )
)

internal object EmptyComponentFingerprint : Fingerprint(
    classFingerprint = EmptyComponentParentFingerprint,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "L",
    parameters = listOf("L")
)

internal object LithoFilterFingerprint : Fingerprint(
    definingClass = EXTENSION_CLASS,
    accessFlags = listOf(AccessFlags.STATIC, AccessFlags.CONSTRUCTOR),
    filters = listOf(
        fieldAccess(
            opcode = Opcode.SPUT_OBJECT,
            definingClass = "this",
            type = EXTENSION_FILTER
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

internal object LithoConverterBufferUpbFeatureFlagFingerprint : Fingerprint(
    returnType = "L",
    filters = listOf(
        literal(45419603L)
    )
)

/**
 * Matches the method returning the accessibility text.
 * Caller must supply the method reference resolved from [AccessibilityIdFingerprint].
 */
internal fun accessibilityTextFingerprint(accessibilityIdMethod: MethodReference) = Fingerprint(
    returnType = "V",
    filters = listOf(
        methodCall(
            opcode = Opcode.INVOKE_INTERFACE,
            parameters = listOf(),
            returnType = "Ljava/lang/String;"
        ),
        methodCall(
            reference = accessibilityIdMethod,
            location = MatchAfterWithin(5)
        )
    ),
    custom = { method, _ ->
        // 'public final synthetic' or 'public final bridge synthetic'.
        AccessFlags.SYNTHETIC.isSet(method.accessFlags)
    }
)

/**
 * Matches the component create method.
 * Caller must supply the defining class of the accessibility ID method.
 */
internal fun componentCreateFingerprint(accessibilityIdDefiningClass: String) = Fingerprint(
    returnType = "L",
    filters = listOf(
        opcode(Opcode.IF_EQZ),
        checkCast(
            type = accessibilityIdDefiningClass,
            location = MatchAfterWithin(5)
        ),
        opcode(Opcode.RETURN_OBJECT),
        string("Element missing correct type extension"),
        string("Element missing type")
    )
)
