/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3579
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.shared.layout.font

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * ResourcesCompat font resource loader: context, resource id, value, style, callback, flags.
 */
internal object FontResourceLoaderFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "Landroid/graphics/Typeface;",
    parameters = listOf("Landroid/content/Context;", "I", "Landroid/util/TypedValue;", "I", "L", "Z", "Z"),
    strings = listOf(string("Font resource ID #0x", StringComparisonType.STARTS_WITH))
)

/**
 * Typeface of the YouTube Sans and Roboto font enum: context, style.
 */
internal object FontEnumTypefaceFingerprint : Fingerprint(
    classFingerprint = Fingerprint(
        strings = listOf("YOUTUBE_SANS_REGULAR", "ROBOTO_REGULAR")
    ),
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Landroid/graphics/Typeface;",
    parameters = listOf("Landroid/content/Context;", "I")
)
