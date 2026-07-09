/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.hide.updatescreen

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.string

internal object AppBlockingCheckResultToStringFingerprint : Fingerprint(
    name = "toString",
    returnType = "Ljava/lang/String;",
    parameters = listOf(),
    filters = listOf(
        string("AppBlockingCheckResult{intent=")
    )
)
