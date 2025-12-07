package app.morphe.patches.shared.misc.gms

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

internal val googlePlayUtilityFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "I",
    parameters = listOf("L", "I"),
    strings = listOf(
        "This should never happen.",
        "MetadataValueReader",
        "com.google.android.gms",
    )
)

internal val serviceCheckFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "V",
    parameters = listOf("L", "I"),
    strings = listOf("Google Play Services not available")
)

internal val gmsCoreSupportFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.STATIC),
    returnType = "Ljava/lang/String;",
    parameters = listOf(),
    custom = { method, classDef ->
        method.name == "getGmsCoreVendorGroupId" && classDef.type == EXTENSION_CLASS_DESCRIPTOR
    }
)

internal val originalPackageNameExtensionFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.STATIC),
    returnType = "Ljava/lang/String;",
    parameters = listOf(),
    custom = { methodDef, classDef ->
        methodDef.name == "getOriginalPackageName" && classDef.type == EXTENSION_CLASS_DESCRIPTOR
    }
)
