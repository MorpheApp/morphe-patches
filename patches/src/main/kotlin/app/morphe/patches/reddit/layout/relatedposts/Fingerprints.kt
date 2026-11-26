/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5181
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.relatedposts

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * Lazy list key method of 'RelatedPostLazyListSection'.
 */
private object RelatedPostSectionKeyFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Ljava/lang/String;",
    parameters = listOf(),
    filters = listOf(
        string("related_posts_")
    )
)

/**
 * Adds the 'Related Posts' label and posts below the comments to the lazy list.
 */
internal object RelatedPostSectionContentFingerprint : Fingerprint(
    classFingerprint = RelatedPostSectionKeyFingerprint,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("L", "L", "L", "L")
)
