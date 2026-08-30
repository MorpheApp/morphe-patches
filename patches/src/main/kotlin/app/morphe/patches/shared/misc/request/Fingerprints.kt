/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.shared.misc.request

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

private object RequestPriorityEnumFingerprint : Fingerprint(
    name = "<clinit>",
    filters = listOf(
        string("LOW"),
        string("NORMAL"),
        string("HIGH"),
        string("IMMEDIATE")
    )
)

internal fun BytecodePatchContext.getBuildRequestFingerprint() = object : Fingerprint(
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
            definingClass = RequestPriorityEnumFingerprint.originalClassDef.type,
            name = "ordinal"
        ),
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            name = "setPriority"
        ),
    )
) {}
