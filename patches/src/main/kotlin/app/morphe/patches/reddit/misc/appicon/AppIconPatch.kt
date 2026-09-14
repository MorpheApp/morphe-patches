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
    "Lapp/morphe/extension/reddit/patches/AppIconPatch;"

@Suppress("unused")
val appIconPatch = bytecodePatch(
    name = "App icon",
    description = "Adds an option to select from the Reddit app icons available in the manifest."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(settingsPatch)

    execute {
        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}
