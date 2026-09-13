/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2937
 *
 * See the included NOTICE file for GPLv3 Section 7 terms and conditions that apply to this code.
 */

package app.morphe.patches.reddit.misc.appicon

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.setExtensionIsPatchIncluded

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/reddit/patches/CustomAppIconPatch;"

@Suppress("unused")
val customAppIconPatch = bytecodePatch(
    name = "Custom app icon",
    description = "Adds an option to select an existing manifest app icon."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(settingsPatch)

    execute {
        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}