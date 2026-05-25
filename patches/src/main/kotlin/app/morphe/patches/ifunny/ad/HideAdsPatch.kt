/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */
package app.morphe.patches.ifunny.ad

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.ifunny.shared.Constants.COMPATIBILITY_IFUNNY
import app.morphe.util.returnEarly

@Suppress("unused")
val hideAdsPatch = bytecodePatch(
    name = "Hide ads",
    description = "Hides ads."
) {
    compatibleWith(COMPATIBILITY_IFUNNY)

    execute {
        IsAdsDisabledFingerprint.method.returnEarly(true)
    }
}
