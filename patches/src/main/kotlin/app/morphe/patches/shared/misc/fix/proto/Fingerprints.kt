/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.patches.shared.misc.fix.proto

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal object EmptyRegistryFingerprint : Fingerprint(
    definingClass = "Lcom/google/protobuf/ExtensionRegistryLite;",
    name ="getGeneratedRegistry",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    parameters = listOf(),
    returnType = "Lcom/google/protobuf/ExtensionRegistryLite;"
)

internal object MessageLiteWriteToFingerprint : Fingerprint(
    definingClass = "Lcom/google/protobuf/MessageLite;",
    name = "writeTo",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.ABSTRACT),
    parameters = listOf("L"),
    returnType = "V"
)

internal object ProtobufClassParseByteArrayFingerprint : Fingerprint(
    name = "parseFrom",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "L",
    parameters = listOf("L", "[B")
)

internal object StreamingDataOuterClassFingerprint : Fingerprint(
    definingClass = "Lcom/google/protos/youtube/api/innertube/StreamingDataOuterClass\$StreamingData;",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf(),
    filters = listOf(
        methodCall(
            opcode = Opcode.INVOKE_INTERFACE,
            parameters = listOf(),
            returnType = "Z"
        ),
        opcode(Opcode.IF_NEZ),
        methodCall(
            opcode = Opcode.INVOKE_STATIC,
            name = "mutableCopy"
        )
    )
)
