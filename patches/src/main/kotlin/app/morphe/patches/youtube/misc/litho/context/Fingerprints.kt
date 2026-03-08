/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
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