/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */
package app.morphe.patches.ifunny.video

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.ifunny.shared.Constants.COMPATIBILITY_IFUNNY
import app.morphe.util.returnEarly

@Suppress("unused")
val saveVideosPatch = bytecodePatch(
    name = "Save videos",
    description = "Unlocks the ability to save videos."
) {
    compatibleWith(COMPATIBILITY_IFUNNY)

    execute {
        CanSaveVideoFingerprint.method.returnEarly(true)
    }
}
