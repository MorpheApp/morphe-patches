/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */
package app.morphe.patches.reddit.layout.viewcount

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.reddit.misc.flag.featureFlagHookPatch
import app.morphe.patches.reddit.misc.flag.hookFeatureFlag
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.setExtensionIsPatchIncluded

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/reddit/patches/ShowViewCountPatch;"

@Suppress("unused")
val showViewCountPatch = bytecodePatch(
    name = "Show view count",
    description = "Adds an option to show the view count of Posts."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(
        settingsPatch,
        featureFlagHookPatch
    )

    execute {

        hookFeatureFlag("$EXTENSION_CLASS_DESCRIPTOR->showViewCount")

        setExtensionIsPatchIncluded(EXTENSION_CLASS_DESCRIPTOR)
    }
}
