package app.morphe.patches.youtube.misc.imageurlhook

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.anyInstruction
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags

internal object OnFailureFingerprint : Fingerprint(
    name = "onFailed",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf(
        "Lorg/chromium/net/UrlRequest;",
        "Lorg/chromium/net/UrlResponseInfo;",
        "Lorg/chromium/net/CronetException;"
    )
)

// Acts as a parent fingerprint.
internal object OnResponseStartedFingerprint : Fingerprint(
    name ="onResponseStarted",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("Lorg/chromium/net/UrlRequest;", "Lorg/chromium/net/UrlResponseInfo;"),
    strings = listOf(
        "Content-Length",
        "Content-Type",
        "identity",
        "application/x-protobuf",
    )
)

internal object OnSucceededFingerprint : Fingerprint(
    name = "onSucceeded",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("Lorg/chromium/net/UrlRequest;", "Lorg/chromium/net/UrlResponseInfo;")
)

internal const val CRONET_URL_REQUEST_CLASS_DESCRIPTOR = "Lorg/chromium/net/impl/CronetUrlRequest;"

internal object RequestFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    custom = { _, classDef ->
        classDef.type == CRONET_URL_REQUEST_CLASS_DESCRIPTOR
    }
)

internal object MessageDigestImageUrlFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    parameters = listOf("Ljava/lang/String;", "L")
)

internal object MessageDigestImageUrlParentFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Ljava/lang/String;",
    parameters = listOf(),
    filters = listOf(
        anyInstruction(
            string("@#&=*+-_.,:!?()/~'%;\$"),
            string("@#&=*+-_.,:!?()/~'%;\$[]"), // 20.38+
        )
    )
)
