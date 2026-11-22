/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5190
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.mediaviewer

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.registersUsed
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.misc.version.is_2026_24_0_or_greater
import app.morphe.patches.reddit.misc.version.is_2026_40_0_or_greater
import app.morphe.patches.reddit.misc.version.versionCheckPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.findInstructionIndicesReversedOrThrow
import app.morphe.util.setExtensionIsPatchIncluded
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import java.util.logging.Logger

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/reddit/patches/SwipeUpForCommentsPatch;"

@Suppress("unused")
val swipeUpForCommentsPatch = patch(
    name = "Swipe up for comments",
    description = "Adds an option to open the comments by swiping up in the media viewer, " +
            "and to swipe sideways for the next post. This patch works with Reddit 2026.24.0 and newer."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(settingsPatch, versionCheckPatch)

    execute {
        // Older versions only chain posts sideways, and swiping up already opens the comments.
        if (!is_2026_24_0_or_greater) {
            return@execute Logger.getLogger(this::class.java.name).warning(
                "'Swipe up for comments' requires Reddit 2026.24.0+"
            )
        }

        if (is_2026_40_0_or_greater) {
            // Several branches jump to the boxing of the lazy value result.
            VerticalChainingFingerprint.let {
                val valueOf = it.instructionMatches.last()
                val register = valueOf.instruction.registersUsed[0]

                it.method.addInstructionsAtControlFlowLabel(
                    valueOf.index,
                    """
                        invoke-static/range { v$register .. v$register }, $EXTENSION_CLASS->allowVerticalChaining(Z)Z
                        move-result v$register
                    """
                )
            }
        } else {
            VerticalChainingLegacyFingerprint.instructionMatches.first().getMethodCalled().apply {
                findInstructionIndicesReversedOrThrow(Opcode.RETURN).forEach { index ->
                    val register = getInstruction<OneRegisterInstruction>(index).registerA
                    addInstructions(
                        index,
                        """
                            invoke-static/range { v$register .. v$register }, $EXTENSION_CLASS->allowVerticalChaining(Z)Z
                            move-result v$register
                        """
                    )
                }
            }
        }

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}
