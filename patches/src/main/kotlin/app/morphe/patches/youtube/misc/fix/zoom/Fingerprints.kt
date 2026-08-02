/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.misc.fix.zoom

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.literal

internal object FullscreenGestureZoomFingerprint :Fingerprint(
    filters = listOf(
        literal(45698813L)
    )
)
