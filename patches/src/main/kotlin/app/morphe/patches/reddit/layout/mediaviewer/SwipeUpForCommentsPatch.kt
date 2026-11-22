/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.mediaviewer

import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.registersUsed
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.misc.version.is_2026_40_0_or_greater
import app.morphe.patches.reddit.misc.version.versionCheckPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.setExtensionIsPatchIncluded
import java.util.logging.Logger

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/reddit/patches/SwipeUpForCommentsPatch;"

@Suppress("unused")
val swipeUpForCommentsPatch = patch(
    name = "Swipe up for comments",
    description = "Adds an option to open the comments by swiping up in the full-screen video player. " +
        "The next video is then a sideways swipe away. This patch works with Reddit 2026.40.0 and newer."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(settingsPatch, versionCheckPatch)

    execute {
        if (!is_2026_40_0_or_greater) {
            return@execute Logger.getLogger(this::class.java.name).warning(
                "'Swipe up for comments' does not work with Reddit 2026.39.0 and earlier"
            )
        }

        // The result of the vertical chaining lazy value, which several branches jump to.
        VerticalChainingFingerprint.let {
            val valueOfIndex = it.instructionMatches.last().index
            val register = it.instructionMatches.last().instruction.registersUsed[0]

            it.method.addInstructionsAtControlFlowLabel(
                valueOfIndex,
                """
                    invoke-static { v$register }, $EXTENSION_CLASS->allowVerticalChaining(Z)Z
                    move-result v$register
                """
            )
        }

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}
