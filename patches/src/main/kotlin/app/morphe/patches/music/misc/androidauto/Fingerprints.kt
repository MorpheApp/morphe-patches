/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.music.misc.androidauto

import app.morphe.patcher.Fingerprint

internal object CheckCertificateFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf("L"),
    strings = listOf(
        "X509",
        "isPartnerSHAFingerprint"
    )
)

/**
 * Anchors [IsGoogleSignedFingerprint] to the class that contains the remote
 * Google-certificates fetch logic.
 */
internal object GoogleCertificatesRemoteFingerprint : Fingerprint(
    returnType = "L",
    parameters = listOf("Ljava/lang/String;"),
    strings = listOf("Failed to get Google certificates from remote")
)

/**
 * [GoogleSignatureVerifier.c(String)][defpackage.tcn.c] — the boolean entry-point
 * that [AllowlistManager.g][defpackage.kxo.g] calls to decide whether the caller
 * is Google-signed.  Scoped to [GoogleCertificatesRemoteFingerprint] so the
 * patcher never picks up an unrelated `(String)→boolean` method.
 */
internal object IsGoogleSignedFingerprint : Fingerprint(
    classFingerprint = GoogleCertificatesRemoteFingerprint,
    returnType = "Z",
    parameters = listOf("Ljava/lang/String;")
)
