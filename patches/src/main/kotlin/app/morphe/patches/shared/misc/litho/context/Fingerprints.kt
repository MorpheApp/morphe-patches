/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.shared.misc.litho.context

import app.morphe.patcher.Fingerprint

internal const val IDENTIFIER_PROPERTY = ", identifierProperty="
internal const val HORIZONTAL_COLLECTION_SWIPE_PROTECTOR_PROPERTY = "horizontalCollectionSwipeProtector="
internal const val HEIGHT_CONSTRAINT_PROPERTY = "heightConstraint="

internal object ConversionContextToStringFingerprint : Fingerprint(
    name = "toString",
    parameters = listOf(),
    returnType = "Ljava/lang/String;",
    strings = listOf(
        "ConversionContext{", // Partial string match.
        ", widthConstraint=",
        ", templateLoggerFactory=",
        ", rootDisposableContainer=",
        IDENTIFIER_PROPERTY
    )
)
