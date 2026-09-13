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

/**
 * Adds a standalone app icon picker to the Morphe settings screen.
 *
 * Reads all activity-alias entries from the Reddit APK manifest via PackageManager,
 * shows them in a dialog with icon previews, and applies the selected icon using
 * PackageManager.setComponentEnabledSetting() — the same mechanism Reddit uses
 * internally, but without any premium gate.
 *
 * No dependency on Reddit's LauncherIcons infrastructure or premium subscription.
 */
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