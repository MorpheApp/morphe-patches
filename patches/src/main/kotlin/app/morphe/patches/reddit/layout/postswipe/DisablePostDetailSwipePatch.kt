/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5295
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.postswipe

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.registersUsed
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.setExtensionIsPatchIncluded

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/reddit/patches/DisablePostDetailSwipePatch;"

@Suppress("unused")
val disablePostDetailSwipePatch = patch(
    name = "Disable post detail swipe",
    description = "Adds an option to stop swiping sideways in a post from opening the next or previous post."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(settingsPatch)

    execute {
        PostDetailPagerSetupFingerprint.let {
            val lastIndex = it.instructionMatches.last().index

            it.method.apply {
                val pagerRegister = getInstruction(lastIndex).registersUsed[0]

                addInstructions(
                    lastIndex + 1,
                    "invoke-static/range { v$pagerRegister .. v$pagerRegister }, " +
                        "$EXTENSION_CLASS->onPostDetailPagerCreated(Landroid/view/View;)V"
                )
            }
        }

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}
