package app.morphe.patches.music.misc.androidauto

import app.morphe.patcher.Fingerprint

internal object CheckCertificateFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf("Ljava/lang/String;"),
    strings = listOf(
        "X509",
        "Failed to get certificate" // Partial String match.
    )
)