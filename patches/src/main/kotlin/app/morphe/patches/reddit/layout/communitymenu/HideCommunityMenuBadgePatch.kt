/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5303
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.communitymenu

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.findFreeRegister
import app.morphe.patcher.util.registersUsed
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.setExtensionIsPatchIncluded

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/reddit/patches/HideCommunityMenuBadgePatch;"

@Suppress("unused")
val hideCommunityMenuBadgePatch = patch(
    name = "Hide community menu badge",
    description = "Adds an option to hide the red dot on the community menu button."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(settingsPatch)

    execute {
        CommunityMenuBadgeFingerprint.let {
            it.method.apply {
                val insertIndex = it.instructionMatches[0].index
                val constStringRegister = getInstruction(it.instructionMatches[1].index).registersUsed[0]
                val register = if (constStringRegister <= 15) {
                    constStringRegister
                } else {
                    findFreeRegister(insertIndex)
                }

                addInstructionsWithLabels(
                    insertIndex,
                    """
                        invoke-static { }, $EXTENSION_CLASS->hideCommunityMenuBadge()Z
                        move-result v$register
                        if-nez v$register, :hide_badge
                    """,
                    ExternalLabel("hide_badge", getInstruction(it.instructionMatches[3].index))
                )
            }
        }

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}
