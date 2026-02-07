package app.morphe.patches.all.misc.signature

import app.morphe.patcher.Fingerprint

internal const val PACKAGE_NAME = "PACKAGE_NAME"
internal const val CERTIFICATE_BASE64 = "CERTIFICATE_BASE64"

internal val applicationFingerprint = Fingerprint(
    returnType = "V",
    custom = { _, classDef ->
        classDef.superclass == "Landroid/app/Application;"
    }
)

internal val spoofSignatureFingerprint = Fingerprint(
    definingClass = "Lapp/morphe/extension/all/misc/signature/SpoofSignaturePatch;",
    name = "<clinit>",
    returnType = "V",
    strings = listOf(
        PACKAGE_NAME,
        CERTIFICATE_BASE64
    )
)
