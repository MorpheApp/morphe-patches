/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.patches.youtube.misc.litho.context

import app.morphe.patcher.Fingerprint

internal const val IDENTIFIER_PROPERTY = ", identifierProperty="
internal const val STRING_BUILDER_TYPE = "Ljava/lang/StringBuilder;"
internal const val STRING_TYPE = "Ljava/lang/String;"

internal object ConversionContextToStringFingerprint : Fingerprint(
    name = "toString",
    parameters = listOf(),
    returnType = STRING_TYPE,
    strings = listOf(
        "ConversionContext{", // Partial string match.
        ", widthConstraint=",
        ", heightConstraint=",
        ", templateLoggerFactory=",
        ", rootDisposableContainer=",
        IDENTIFIER_PROPERTY
    )
)