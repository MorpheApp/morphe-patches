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
    returnType = "V",
    strings = listOf(
        PACKAGE_NAME,
        CERTIFICATE_BASE64
    ),
    custom = { method, _ ->
        method.definingClass.endsWith("/SpoofSignaturePatch;") &&
                method.name == "<clinit>"
    }
)
