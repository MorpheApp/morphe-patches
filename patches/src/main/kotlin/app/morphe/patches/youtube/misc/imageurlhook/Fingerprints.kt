package app.morphe.patches.youtube.misc.imageurlhook

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.anyInstruction
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags

internal val onFailureFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf(
        "Lorg/chromium/net/UrlRequest;",
        "Lorg/chromium/net/UrlResponseInfo;",
        "Lorg/chromium/net/CronetException;"
    ),
    custom = { method, _ ->
        method.name == "onFailed"
    }
)

// Acts as a parent fingerprint.
internal val onResponseStartedFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("Lorg/chromium/net/UrlRequest;", "Lorg/chromium/net/UrlResponseInfo;"),
    strings = listOf(
        "Content-Length",
        "Content-Type",
        "identity",
        "application/x-protobuf",
    ),
    custom = { method, _ ->
        method.name == "onResponseStarted"
    }
)

internal val onSucceededFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("Lorg/chromium/net/UrlRequest;", "Lorg/chromium/net/UrlResponseInfo;"),
    custom = { method, _ ->
        method.name == "onSucceeded"
    }
)

internal const val CRONET_URL_REQUEST_CLASS_DESCRIPTOR = "Lorg/chromium/net/impl/CronetUrlRequest;"

internal val requestFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    custom = { _, classDef ->
        classDef.type == CRONET_URL_REQUEST_CLASS_DESCRIPTOR
    }
)

internal val messageDigestImageUrlFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    parameters = listOf("Ljava/lang/String;", "L")
)

internal val messageDigestImageUrlParentFingerprint = Fingerprint(
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
