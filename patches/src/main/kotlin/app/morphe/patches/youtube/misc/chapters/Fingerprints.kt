/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2100
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */
package app.morphe.patches.youtube.misc.chapters

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.checkCast
import com.android.tools.smali.dexlib2.AccessFlags

internal object TimelineMarkerFingerprint : Fingerprint (
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    name = "toString",
    returnType = "Ljava/lang/String;",
    parameters = listOf(),
    strings = listOf("TimelineMarker[title=",  ", startMillis=", ", endMillis=")
)

internal fun getTimelineMarkersArrayFingerprint(timelineMarkerClassName: String) = object : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "[$timelineMarkerClassName",
    parameters = listOf("L"),
    filters = listOf(
        checkCast("[$timelineMarkerClassName")
    )
) {}

