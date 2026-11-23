/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.hidepost

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.registersUsed
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.misc.version.is_2026_40_0_or_greater
import app.morphe.patches.reddit.misc.version.is_2026_44_0_or_greater
import app.morphe.patches.reddit.misc.version.versionCheckPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.setExtensionIsPatchIncluded
import java.util.logging.Logger

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/reddit/patches/AlwaysShowHidePatch;"

@Suppress("unused")
val alwaysShowHidePatch = patch(
    name = "Always show Hide",
    description = "Adds an option to always show Hide in the menu of feed posts, even when Reddit " +
        "replaces it with recommendation options. This patch works with Reddit 2026.40.0 and newer."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(settingsPatch, versionCheckPatch)

    execute {
        if (!is_2026_40_0_or_greater) {
            return@execute Logger.getLogger(this::class.java.name).warning(
                "'Always show Hide' does not work with Reddit 2026.39.0 and earlier"
            )
        }

        val fingerprint = if (is_2026_44_0_or_greater) {
            PostMenuHideCheckFingerprint
        } else {
            PostMenuHideCheckLegacyFingerprint
        }

        fingerprint.method.apply {
            val moveResultIndex = fingerprint.instructionMatches[2].index
            val register = getInstruction(moveResultIndex).registersUsed[0]
            val invokeInstruction = if (register > 15) {
                "invoke-static/range { v$register .. v$register }"
            } else {
                "invoke-static { v$register }"
            }

            addInstructions(
                moveResultIndex + 1,
                """
                    $invokeInstruction, $EXTENSION_CLASS->hideReplaced(Z)Z
                    move-result v$register
                """
            )
        }

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}
