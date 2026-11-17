/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.misc.privacy

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import app.morphe.patcher.newInstance
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * Requests a short link (/r/<subreddit>/s/<id>) for a shared link.
 * Opening a short link redirects to the full link with a unique share_id query parameter.
 * If the request fails, the full link is shared instead.
 */
internal object GetShortUrlFingerprint : Fingerprint(
    definingClass = "Lcom/reddit/sharing/data/source/remote/a;",
    returnType = "Ljava/lang/Object;",
    filters = listOf(
        newInstance($$"Lcom/reddit/sharing/data/source/remote/RemoteGqlSharingDataSource$getShortUrl$1;")
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
