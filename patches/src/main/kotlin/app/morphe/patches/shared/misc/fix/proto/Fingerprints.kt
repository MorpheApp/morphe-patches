package app.morphe.patches.shared.misc.fix.proto

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

internal object EmptyRegistryFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    parameters = listOf(),
    returnType = "Lcom/google/protobuf/ExtensionRegistryLite;",
    custom = { method, classDef ->
        classDef.type == "Lcom/google/protobuf/ExtensionRegistryLite;"
                && method.name == "getGeneratedRegistry"
    }
)

internal object MessageLiteWriteToFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.ABSTRACT),
    parameters = listOf("L"),
    returnType = "V",
    custom = { method, classDef ->
        classDef.type == "Lcom/google/protobuf/MessageLite;"
                && method.name == "writeTo"
    }
)
