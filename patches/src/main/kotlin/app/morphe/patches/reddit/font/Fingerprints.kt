/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */
package app.morphe.patches.reddit.font

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.BytecodePatchContext
import com.android.tools.smali.dexlib2.AccessFlags

internal enum class TypefaceCompatCreateFromResourcesFontFileVariant {
    LEGACY_STATIC,
    MODERN_VIRTUAL,
}

internal data class TypefaceCompatCreateFromResourcesFontFileTarget(
    val variant: TypefaceCompatCreateFromResourcesFontFileVariant,
    val method: app.morphe.patcher.util.proxy.mutableTypes.MutableMethod,
)

internal object LegacyTypefaceCompatCreateFromResourcesFontFileFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "Landroid/graphics/Typeface;",
    parameters = listOf(
        "Landroid/content/res/Resources;",
        "I",
        "Ljava/lang/String;",
        "I",
        "I",
    ),
    filters = listOf(
        methodCall(smali = "Landroid/graphics/fonts/Font\$Builder;-><init>(Landroid/content/res/Resources;I)V"),
    ),
)

internal object ModernTypefaceCompatCreateFromResourcesFontFileFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Landroid/graphics/Typeface;",
    parameters = listOf(
        "Landroid/content/Context;",
        "Landroid/content/res/Resources;",
        "I",
        "Ljava/lang/String;",
        "I",
    ),
    filters = listOf(
        methodCall(smali = "Landroid/graphics/fonts/Font\$Builder;-><init>(Landroid/content/res/Resources;I)V"),
    ),
)

context(_: BytecodePatchContext)
internal fun resolveTypefaceCompatCreateFromResourcesFontFileTarget(): TypefaceCompatCreateFromResourcesFontFileTarget {
    listOf(
        TypefaceCompatCreateFromResourcesFontFileVariant.LEGACY_STATIC to LegacyTypefaceCompatCreateFromResourcesFontFileFingerprint,
        TypefaceCompatCreateFromResourcesFontFileVariant.MODERN_VIRTUAL to ModernTypefaceCompatCreateFromResourcesFontFileFingerprint,
    ).forEach { (variant, fingerprint) ->
        try {
            return TypefaceCompatCreateFromResourcesFontFileTarget(variant, fingerprint.method)
        } catch (_: Throwable) {
            // Try the next method shape.
        }
    }

    throw IllegalStateException("Failed to match any typeface creation method shape")
}