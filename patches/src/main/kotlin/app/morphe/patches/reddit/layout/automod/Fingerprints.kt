/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5156
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.automod

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.string

/**
 * Resolves the SetComments class by its toString representation.
 */
private object SetCommentsToStringFingerprint : Fingerprint(
    name = "toString",
    returnType = "Ljava/lang/String;",
    parameters = listOf(),
    filters = listOf(
        string("SetComments(comments="),
    ),
)

/**
 * Constructor of the SetComments class that receives the comments list as its first parameter.
 */
internal object SetCommentsConstructorFingerprint : Fingerprint(
    classFingerprint = SetCommentsToStringFingerprint,
    name = "<init>",
    returnType = "V",
)
