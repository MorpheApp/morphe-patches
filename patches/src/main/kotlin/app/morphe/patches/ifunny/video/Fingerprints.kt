/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */
package app.morphe.patches.ifunny.video

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.string

internal object CanSaveVideoFingerprint : Fingerprint(
    definingClass = "Lmobi/ifunny/social/share/video/model/SaveContentCriterion;",
    returnType = "Z",
    parameters = listOf(),
    filters = listOf(
        string("getUserInfo(...)")
    )
)
