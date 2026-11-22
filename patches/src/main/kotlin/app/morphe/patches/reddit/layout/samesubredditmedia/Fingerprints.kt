/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5155
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.samesubredditmedia

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import com.android.tools.smali.dexlib2.AccessFlags.FINAL
import com.android.tools.smali.dexlib2.AccessFlags.PUBLIC

private const val MEDIA_CONTEXT = "Lcom/reddit/domain/model/media/MediaContext;"

/**
 * Loads posts for the full-screen media viewer.
 */
internal object MediaViewerRequestFingerprint : Fingerprint(
    accessFlags = listOf(PUBLIC, FINAL),
    returnType = "Ljava/lang/Object;",
    filters = listOf(
        methodCall(definingClass = MEDIA_CONTEXT, name = "getSeedSubredditIds"),
        methodCall(definingClass = MEDIA_CONTEXT, name = "getListingType"),
        methodCall(definingClass = "Lcom/reddit/domain/model/listing/Listing;", name = "<init>")
    )
)
