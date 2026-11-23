/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5242
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.bottombar

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.patch
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.patches.reddit.shared.NavBarStateChangeFingerprint
import app.morphe.util.setExtensionIsPatchIncluded

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/reddit/patches/DisableBottomBarAutoHidePatch;"

@Suppress("unused")
val disableBottomBarAutoHidePatch = patch(
    name = "Disable bottom bar auto-hide",
    description = "Adds an option to keep the bottom navigation bar visible while scrolling."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(settingsPatch)

    execute {
        NavBarStateChangeFingerprint.method.addInstructions(
            0,
            """
                invoke-static { p1 }, $EXTENSION_CLASS->getNavBarShownFraction(F)F
                move-result p1
            """
        )

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}
