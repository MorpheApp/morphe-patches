/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.bottombar

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.patch
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.misc.version.is_2026_40_0_or_greater
import app.morphe.patches.reddit.misc.version.versionCheckPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.setExtensionIsPatchIncluded
import java.util.logging.Logger

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/reddit/patches/DisableBottomBarAutoHidePatch;"

@Suppress("unused")
val disableBottomBarAutoHidePatch = patch(
    name = "Disable bottom bar auto-hide",
    description = "Adds an option to keep the bottom navigation bar visible while scrolling. " +
        "This patch works with Reddit 2026.40.0 and newer."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(settingsPatch, versionCheckPatch)

    execute {
        if (!is_2026_40_0_or_greater) {
            return@execute Logger.getLogger(this::class.java.name).warning(
                "'Disable bottom bar auto-hide' does not work with Reddit 2026.39.0 and earlier"
            )
        }

        BottomBarStateChangeFingerprint.method.addInstructions(
            0,
            """
                invoke-static { p1 }, $EXTENSION_CLASS->getBarVisibleFraction(F)F
                move-result p1
            """
        )

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}
