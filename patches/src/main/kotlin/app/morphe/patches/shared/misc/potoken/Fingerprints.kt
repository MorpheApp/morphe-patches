/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2618
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.shared.misc.potoken

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.methodCall
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal object ServiceBindIntentUtilsFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    filters = listOf(
        string("ServiceBindIntentUtils"),
        string("serviceActionBundleKey"),
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            smali = "Landroid/os/Bundle;->putString(Ljava/lang/String;Ljava/lang/String;)V",
            location = MatchAfterWithin(5)
        ),
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            smali = "Landroid/content/ContentResolver;->acquireUnstableContentProviderClient(Landroid/net/Uri;)Landroid/content/ContentProviderClient;",
            location = MatchAfterWithin(10)
        )
    )
)

private const val MEDIA_INTERFACES = "Lcom/google/android/libraries/youtube/media/interfaces/"

/**
 * The app's own implementation of the media request interface, which native code calls to start a request.
 */
internal object NetFetchStartFetchTaskFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "${MEDIA_INTERFACES}NetFetchTask;",
    parameters = listOf("${MEDIA_INTERFACES}HttpRequest;", "${MEDIA_INTERFACES}NetFetchCallbacks;"),
    custom = { _, classDef ->
        classDef.superclass == "${MEDIA_INTERFACES}NetFetch;" && !classDef.type.endsWith($$"$CppProxy;")
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
