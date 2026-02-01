package app.morphe.patches.shared.misc.fix.proto

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

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
