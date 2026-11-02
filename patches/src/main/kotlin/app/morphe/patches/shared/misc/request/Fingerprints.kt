/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2688
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.shared.misc.request

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.methodCall
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal object BuildRequestFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "Lorg/chromium/net/UrlRequest", // UrlRequest; or UrlRequest$Builder;
    filters = listOf(
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            name = "newUrlRequestBuilder",
            parameters = listOf(
                "Ljava/lang/String;",
                $$"Lorg/chromium/net/UrlRequest$Callback;",
                "Ljava/util/concurrent/Executor;"
            )
        ),
        methodCall(
            opcode = Opcode.INVOKE_INTERFACE,
            smali = "Ljava/util/Map;->entrySet()Ljava/util/Set;",
            location = MatchAfterWithin(15)
        ),
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            name = "ordinal"
        ),
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            name = "setPriority"
        )
    )
)

private const val MEDIA_INTERFACES = "Lcom/google/android/libraries/youtube/media/interfaces/"

internal const val HTTP_REQUEST_CLASS_TYPE = "${MEDIA_INTERFACES}HttpRequest;"

/**
 * The parameters depend on the app version. Newer versions also take a boolean.
 */
internal object HttpRequestConstructorFingerprint : Fingerprint(
    definingClass = HTTP_REQUEST_CLASS_TYPE,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR)
)

/**
 * The app's own implementation of the media request interface, which native code calls to start a request.
 */
internal object NetFetchStartFetchTaskFingerprint : Fingerprint(
    superclass = "${MEDIA_INTERFACES}NetFetch;",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "${MEDIA_INTERFACES}NetFetchTask;",
    parameters = listOf(HTTP_REQUEST_CLASS_TYPE, "${MEDIA_INTERFACES}NetFetchCallbacks;"),
    custom = { _, classDef ->
        !classDef.type.endsWith($$"$CppProxy;")
    }
)

/**
 * Receives each chunk of a media response before native code reads it.
 * The native method it calls has the native pointer as an extra parameter.
 */
internal object NetFetchResponseChunkFingerprint : Fingerprint(
    definingClass = $$"$${MEDIA_INTERFACES}NetFetchCallbacks$CppProxy;",
    returnType = "V",
    parameters = listOf("Ljava/nio/ByteBuffer;")
)
