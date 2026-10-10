/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.mediaheight

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * Height of a gallery, from the size of its images and the media width.
 * Capped to 4:3 of the media width.
 */
internal object GalleryHeightFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
    returnType = "I",
    parameters = listOf("I", "Ljava/util/List;"),
    filters = listOf(
        literal(4f / 3f)
    )
)

/**
 * Height of a feed gallery for a given media width.
 * Without image sizes, capped to 4:3 of the media width.
 */
internal object FeedGalleryHeightForWidthFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
    returnType = "F",
    parameters = listOf("Z", "I", "L", "I", "L"),
    filters = listOf(
        methodCall(smali = "Ljava/lang/Math;->min(II)I")
    )
)

/**
 * Feed video composable. With a maximum aspect ratio set, caps the height to 4:3 of the media width.
 */
internal object FeedVideoFingerprint : Fingerprint(
    returnType = "V",
    filters = listOf(
        string("_media_video"),
        literal(4f / 3f)
    )
)
