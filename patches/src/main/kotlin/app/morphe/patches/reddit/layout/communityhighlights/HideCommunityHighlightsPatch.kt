/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5297
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.communityhighlights

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.registersUsed
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.setExtensionIsPatchIncluded

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/reddit/patches/HideCommunityHighlightsPatch;"

@Suppress("unused")
val hideCommunityHighlightsPatch = patch(
    name = "Hide community highlights",
    description = "Adds an option to hide the Community highlights section at the top of subreddits."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(settingsPatch)

    execute {
        CommunityHighlightsFingerprint.let {
            val moveResultIndex = it.instructionMatches[2].index
            it.method.apply {
                val register = getInstruction(moveResultIndex).registersUsed[0]

                addInstructions(
                    moveResultIndex + 1,
                    """
                        invoke-static/range { v$register .. v$register }, $EXTENSION_CLASS->hideCommunityHighlights(Ljava/lang/Boolean;)Ljava/lang/Boolean;
                        move-result-object v$register
                    """
                )
            }
        }

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}
