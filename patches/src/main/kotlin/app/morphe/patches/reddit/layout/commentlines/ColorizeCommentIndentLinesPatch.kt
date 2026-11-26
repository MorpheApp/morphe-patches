/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5241
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.commentlines

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.registersUsed
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.setExtensionIsPatchIncluded

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/reddit/patches/ColorizeCommentIndentLinesPatch;"

@Suppress("unused")
val colorizeCommentIndentLinesPatch = patch(
    name = "Colorize comment indent lines",
    description = "Adds an option to give each reply level in a comment thread its own line color."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(settingsPatch)

    execute {
        CommentIndentLinesFingerprint.let {
            val drawLineMatch = it.instructionMatches[3]
            // Range call: receiver, color (wide), start (wide), end (wide), strokeWidth, cap, pathEffect, alpha.
            val drawLineRegisters = drawLineMatch.instruction.registersUsed
            val colorRegister = drawLineRegisters[1]
            val alphaRegister = drawLineRegisters[10]
            val depthRegister = it.instructionMatches[4].instruction.registersUsed[0]

            if (maxOf(depthRegister, colorRegister + 1, alphaRegister) > 15) {
                throw PatchException(
                    "Registers out of range: v$depthRegister v$colorRegister v$alphaRegister"
                )
            }

            it.method.addInstructions(
                drawLineMatch.index,
                """
                    invoke-static { v$depthRegister, v$colorRegister, v${colorRegister + 1} }, $EXTENSION_CLASS->getLineColor(IJ)J
                    move-result-wide v$colorRegister
                    invoke-static { v$alphaRegister }, $EXTENSION_CLASS->getLineAlpha(F)F
                    move-result v$alphaRegister
                """
            )
        }

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}
