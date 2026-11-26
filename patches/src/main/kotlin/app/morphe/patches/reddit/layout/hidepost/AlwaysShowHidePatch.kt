/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5243
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.hidepost

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.patch
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.misc.version.is_2026_40_0_or_greater
import app.morphe.patches.reddit.misc.version.is_2026_42_0_or_greater
import app.morphe.patches.reddit.misc.version.versionCheckPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.setExtensionIsPatchIncluded
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import java.util.logging.Logger

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/reddit/patches/AlwaysShowHidePatch;"

@Suppress("unused")
val alwaysShowHidePatch = patch(
    name = "Always show Hide",
    description = "Adds an option to always show Hide in the menu of feed posts, " +
            "also when Reddit replaces it with recommendation options. " +
            "This patch works with Reddit 2026.40.0 and newer."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(settingsPatch, versionCheckPatch)

    execute {
        if (!is_2026_40_0_or_greater) {
            return@execute Logger.getLogger(this::class.java.name).warning(
                "'Always show Hide' requires Reddit 2026.40.0+"
            )
        }

        // Match indexes of the move-result of each check, last first so earlier indexes stay valid.
        val (fingerprint, moveResultMatchIndexes) = if (is_2026_42_0_or_greater) {
            PostMenuHideCheckFingerprint to listOf(2)
        } else {
            PostMenuHideCheckLegacyFingerprint to listOf(4, 2)
        }

        fingerprint.method.apply {
            moveResultMatchIndexes.forEach { matchIndex ->
                val index = fingerprint.instructionMatches[matchIndex].index
                val register = getInstruction<OneRegisterInstruction>(index).registerA

                addInstructions(
                    index + 1,
                    """
                        invoke-static/range { v$register .. v$register }, $EXTENSION_CLASS->isHideReplaced(Z)Z
                        move-result v$register
                    """
                )
            }
        }

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}
