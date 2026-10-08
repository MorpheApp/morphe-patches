/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3579
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.font

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

/**
 * Typeface registry lookup: context, font type, weight, style, font settings.
 */
internal object TypefaceRegistryFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Landroid/graphics/Typeface;",
    parameters = listOf("Landroid/content/Context;", "I", "I", "I", "Ljava/lang/String;"),
    filters = listOf(
        // Font provider lookup: context, weight, style, font settings.
        methodCall(
            opcode = Opcode.INVOKE_INTERFACE,
            parameters = listOf("Landroid/content/Context;", "I", "I", "Ljava/lang/String;"),
            returnType = "Landroid/graphics/Typeface;"
        )
    )
)

/**
 * ResourcesCompat font resource loader: context, resource id, value, style, callback, flags.
 */
internal object FontResourceLoaderFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "Landroid/graphics/Typeface;",
    parameters = listOf("Landroid/content/Context;", "I", "Landroid/util/TypedValue;", "I", "L", "Z", "Z"),
    strings = listOf("Font resource ID #0x")
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
