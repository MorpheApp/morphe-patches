/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */
package app.morphe.patches.reddit.layout.ask

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.reddit.misc.flag.featureFlagHookPatch
import app.morphe.patches.reddit.misc.flag.hookFeatureFlag
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.setExtensionIsPatchIncluded

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/reddit/patches/HideAskButtonPatch;"

@Suppress("unused")
val hideAskButtonPatch = bytecodePatch(
    name = "Hide Ask button",
    description = "Adds an option to hide Ask button in the search bar."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(
        settingsPatch,
        featureFlagHookPatch
    )

    execute {

        hookFeatureFlag("$EXTENSION_CLASS_DESCRIPTOR->hideAskButton")

        setExtensionIsPatchIncluded(EXTENSION_CLASS_DESCRIPTOR)
    }
}
