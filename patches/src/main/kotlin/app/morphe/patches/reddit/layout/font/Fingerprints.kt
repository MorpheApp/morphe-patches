/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */
package app.morphe.patches.reddit.layout.font

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * Matches the AndroidX (obfuscated) `TypefaceCompat.createFromResourcesFontFile`
 * method, which is the single choke point used by both classic Views and
 * Jetpack Compose to build a [android.graphics.Typeface] from an
 * `R.font.*` resource. The class is R8-renamed each release (e.g. `Ln2/e;`),
 * so the fingerprint targets the stable static method shape:
 *
 *   `public static fun (Resources, int, String, int, int): Typeface`
 *
 * plus the unique direct call to `Font.Builder.<init>(Resources, int)`,
 * which only this resource-loading variant performs.
 */
internal object TypefaceCompatCreateFromResourcesFontFileFingerprint : Fingerprint(
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
        methodCall(smali = "Landroid/graphics/fonts/Font${'$'}Builder;-><init>(Landroid/content/res/Resources;I)V"),
    ),
)
