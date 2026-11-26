/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.misc.privacy

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.newInstance
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal const val SHORT_URL_ERROR_CLASS = "Lcom/reddit/sharing/custom/url/shortening/ShortUrlError;"

/**
 * Requests a short link (/r/<subreddit>/s/<id>) for a shared link.
 * Opening a short link redirects to the full link with a unique share_id query parameter.
 * If the request fails, the full link is shared instead.
 *
 * Before 2026.24 it returns the short link, or null if the request failed.
 */
internal object GetShortUrlLegacyFingerprint : Fingerprint(
    returnType = "Ljava/lang/Object;",
    parameters = listOf("Ljava/lang/String;", "Lkotlin/coroutines/jvm/internal/ContinuationImpl;"),
    filters = listOf(
        newInstance($$"Lcom/reddit/sharing/data/source/remote/RemoteGqlSharingDataSource$getShortUrl$1;")
    )
)

/**
 * 2026.24+ returns a result that is either the short link or a [SHORT_URL_ERROR_CLASS] error.
 */
internal object GetShortUrlFingerprint : Fingerprint(
    returnType = "Ljava/lang/Object;",
    parameters = listOf("Ljava/lang/String;", "Lkotlin/coroutines/jvm/internal/ContinuationImpl;"),
    filters = listOf(
        newInstance($$"Lcom/reddit/sharing/data/source/remote/RemoteGqlSharingDataSource$getShortUrl$1;"),
        fieldAccess(
            opcode = Opcode.SGET_OBJECT,
            definingClass = SHORT_URL_ERROR_CLASS,
            name = "UNKNOWN_ERROR"
        ),
        // Constructor of the error result.
        methodCall(
            opcode = Opcode.INVOKE_DIRECT,
            name = "<init>",
            location = MatchAfterImmediately()
        )
    )
)

internal object ShareLinkFormatterFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
    returnType = "Ljava/lang/String;",
    parameters = listOf("Ljava/lang/String;", "Ljava/util/Map;"),
    filters = listOf(
        methodCall(smali = $$"Landroid/net/Uri$Builder;->clearQuery()Landroid/net/Uri$Builder;")
    )
)
