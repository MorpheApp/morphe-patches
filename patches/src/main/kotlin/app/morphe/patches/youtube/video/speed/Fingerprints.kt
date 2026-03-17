/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.patches.youtube.video.speed

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.literal
import com.android.tools.smali.dexlib2.AccessFlags

internal object NewAdvancedQualityMenuStyleFlyout : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    filters = listOf(
        literal(45712556)
    )
)

