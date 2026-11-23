/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.commentlines

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.registersUsed
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.misc.version.is_2026_40_0_or_greater
import app.morphe.patches.reddit.misc.version.versionCheckPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.setExtensionIsPatchIncluded
import java.util.logging.Logger

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/reddit/patches/ColorizeCommentIndentLinesPatch;"

@Suppress("unused")
val colorizeCommentIndentLinesPatch = patch(
    name = "Colorize comment indent lines",
    description = "Adds an option to give each reply level in a comment thread its own line color. This patch works with Reddit 2026.40.0 and newer."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(settingsPatch, versionCheckPatch)

    execute {
        if (!is_2026_40_0_or_greater) {
            return@execute Logger.getLogger(this::class.java.name).warning(
                "'Colorize comment indent lines' does not work with Reddit 2026.39.0 and earlier"
            )
        }

        CommentIndentLinesFingerprint.method.apply {
            val drawIndex = CommentIndentLinesFingerprint.instructionMatches[3].index
            val drawRegisters = getInstruction(drawIndex).registersUsed
            val color = drawRegisters[1]
            val colorHigh = drawRegisters[2]
            val alpha = drawRegisters[10]
            val depth = getInstruction(CommentIndentLinesFingerprint.instructionMatches[4].index).registersUsed[0]

            if (depth > 15 || colorHigh > 15 || alpha > 15) {
                throw PatchException(
                    "Register number exceeds 15: depth=$depth, colorHigh=$colorHigh, alpha=$alpha"
                )
            }

            addInstructions(
                drawIndex,
                """
                    invoke-static { v$depth, v$color, v$colorHigh }, $EXTENSION_CLASS->getLineColor(IJ)J
                    move-result-wide v$color
                    invoke-static { v$alpha }, $EXTENSION_CLASS->getLineAlpha(F)F
                    move-result v$alpha
                """
            )
        }

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}
