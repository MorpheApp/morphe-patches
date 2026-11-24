/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5074
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.videosforyou

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags

private object VideoCarouselSectionKeyFingerprint : Fingerprint(
    returnType = "Ljava/lang/String;",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf(),
    filters = listOf(
        string("video_carousel_section_")
    )
)

internal object VideoCarouselSectionComposeMethodFingerprint : Fingerprint(
    classFingerprint = VideoCarouselSectionKeyFingerprint,
    returnType = "V",
    parameters = listOf("L", "L", "I")
)
