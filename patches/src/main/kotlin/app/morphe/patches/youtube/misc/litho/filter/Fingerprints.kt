package app.morphe.patches.youtube.misc.litho.filter

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.OpcodesFilter
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.string
import app.morphe.util.containsLiteralInstruction
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal val accessibilityIdFingerprint = Fingerprint(
    filters = listOf(
        methodCall(
            opcode = Opcode.INVOKE_INTERFACE,
            parameters = listOf(),
            returnType = "Ljava/lang/String;"
        ),
        string("primary_image", location = MatchAfterWithin(5)),
    )
)

internal val componentCreateFingerprint = Fingerprint(
    filters = listOf(
        string("Element missing correct type extension"),
        string("Element missing type")
    )
)

internal val lithoFilterFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.STATIC, AccessFlags.CONSTRUCTOR),
    custom = { _, classDef ->
        classDef.endsWith("/LithoFilterPatch;")
    }
)

internal val protobufBufferReferenceFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("[B"),
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            definingClass = "this",
            type = "Lcom/google/android/libraries/elements/adl/UpbMessage;"
        ),
        methodCall(
            definingClass = "Lcom/google/android/libraries/elements/adl/UpbMessage;",
            name = "jniDecode"
        )
    )
)

internal val protobufBufferReferenceLegacyFingerprint = Fingerprint(
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

internal val emptyComponentFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.CONSTRUCTOR),
    parameters = listOf(),
    filters = listOf(
        string("EmptyComponent")
    ),
    custom = { _, classDef ->
        classDef.methods.filter { AccessFlags.STATIC.isSet(it.accessFlags) }.size == 1
    }
)

internal val lithoThreadExecutorFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    parameters = listOf("I", "I", "I"),
    custom = { method, classDef ->
        classDef.superclass == "Ljava/util/concurrent/ThreadPoolExecutor;" &&
            method.containsLiteralInstruction(1L) // 1L = default thread timeout.
    }
)

internal val lithoComponentNameUpbFeatureFlagFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Z",
    parameters = listOf(),
    filters = listOf(
        literal(45631264L)
    )
)

internal val lithoConverterBufferUpbFeatureFlagFingerprint = Fingerprint(
    returnType = "L",
    filters = listOf(
        literal(45419603L)
    )
)
