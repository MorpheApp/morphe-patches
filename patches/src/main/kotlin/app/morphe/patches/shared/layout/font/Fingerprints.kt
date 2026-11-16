/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3579
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.shared.layout.font

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.anyInstruction
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

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

internal const val TYPEFACE_TYPE = "Landroid/graphics/Typeface;"

/**
 * Default typeface fields and the extension method that replaces each read.
 */
internal val defaultTypefaceGetters = mapOf(
    "DEFAULT" to "getDefaultTypeface",
    "DEFAULT_BOLD" to "getDefaultBoldTypeface",
    "SANS_SERIF" to "getSansSerifTypeface",
)

/**
 * All methods that read a default typeface, except the extension
 * that uses the original typefaces to create the fonts.
 */
internal object DefaultTypefaceReadFingerprint : Fingerprint(
    filters = listOf(
        anyInstruction(
            *defaultTypefaceGetters.keys.map { name ->
                fieldAccess(
                    opcode = Opcode.SGET_OBJECT,
                    definingClass = TYPEFACE_TYPE,
                    name = name,
                    type = TYPEFACE_TYPE
                )
            }.toTypedArray()
        )
    ),
    custom = { _, classDef ->
        classDef.type != EXTENSION_CLASS
    }
)

/**
 * All methods that call Typeface.create(String, int), except the extension.
 */
internal object FamilyTypefaceCreateFingerprint : Fingerprint(
    filters = listOf(
        methodCall(
            opcodes = listOf(Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE),
            definingClass = TYPEFACE_TYPE,
            name = "create",
            parameters = listOf("Ljava/lang/String;", "I"),
            returnType = TYPEFACE_TYPE
        )
    ),
    custom = { _, classDef ->
        classDef.type != EXTENSION_CLASS
    }
)
