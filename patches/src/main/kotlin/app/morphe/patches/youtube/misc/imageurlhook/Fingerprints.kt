/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.misc.imageurlhook

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.anyInstruction
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags

internal object OnFailureFingerprint : Fingerprint(
    classFingerprint = OnResponseStartedFingerprint,
    name = "onFailed",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf(
        "Lorg/chromium/net/UrlRequest;",
        "Lorg/chromium/net/UrlResponseInfo;",
        "Lorg/chromium/net/CronetException;"
    )
)

private object OnResponseStartedFingerprint : Fingerprint(
    name ="onResponseStarted",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("Lorg/chromium/net/UrlRequest;", "Lorg/chromium/net/UrlResponseInfo;"),
    strings = listOf(
        "Content-Length",
        "Content-Type",
        "identity",
        "application/x-protobuf",
        // FIXME? This matches two different implementations that are very similar but different.
        //        This string uniquely identifies the method that has been used, but this may need
        //        to hook each class.
        "UnaryScanner instances cannot be reused",
    )
)

internal object OnSucceededFingerprint : Fingerprint(
    classFingerprint = OnResponseStartedFingerprint,
    name = "onSucceeded",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("Lorg/chromium/net/UrlRequest;", "Lorg/chromium/net/UrlResponseInfo;")
)

internal const val CRONET_URL_REQUEST_CLASS = "Lorg/chromium/net/impl/CronetUrlRequest;"

internal object RequestFingerprint : Fingerprint(
    definingClass = CRONET_URL_REQUEST_CLASS,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR)
)

private object MessageDigestImageURLParentFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Ljava/lang/String;",
    parameters = listOf(),
    filters = listOf(
        anyInstruction(
            string("@#&=*+-_.,:!?()/~'%;$"),
            string("@#&=*+-_.,:!?()/~'%;$[]"), // 20.38+
        )
    )
)

internal object MessageDigestImageURLFingerprint : Fingerprint(
    classFingerprint = MessageDigestImageURLParentFingerprint,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    parameters = listOf("Ljava/lang/String;", "L")
)
