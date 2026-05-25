/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */
package app.morphe.patches.ifunny.premium

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.ifunny.shared.Constants.COMPATIBILITY_IFUNNY
import app.morphe.util.returnEarly

@Suppress("unused")
val unlockPremiumPatch = bytecodePatch(
    name = "Unlock premium",
    description = "Unlocks premium features and the ability to save videos without watermarks."
) {
    compatibleWith(COMPATIBILITY_IFUNNY)

    execute {
        IsUserPremiumFingerprint.method.returnEarly(true)
    }
}
