/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.misc.backtoexit

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.registersUsed
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.misc.version.is_2026_40_0_or_greater
import app.morphe.patches.reddit.misc.version.versionCheckPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.setExtensionIsPatchIncluded
import java.util.logging.Logger

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/reddit/patches/DisableRefreshOnBackPatch;"

@Suppress("unused")
val disableRefreshOnBackPatch = patch(
    name = "Disable refresh on back to exit",
    description = "Adds an option to exit the app with back on the home feed without first scrolling to the top " +
        "and reloading. This patch works with Reddit 2026.40.0 and newer."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(settingsPatch, versionCheckPatch)

    execute {
        if (!is_2026_40_0_or_greater) {
            return@execute Logger.getLogger(this::class.java.name).warning(
                "'Disable refresh on back to exit' does not work with Reddit 2026.39.0 and earlier"
            )
        }

        BackToExitFingerprint.let {
            it.method.apply {
                hookResult(it.instructionMatches[5].index, "showExitHint")
                hookResult(it.instructionMatches[2].index, "isFeedScrolled")
            }
        }

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}

private fun MutableMethod.hookResult(index: Int, methodName: String) {
    val register = getInstruction(index).registersUsed[0]
    addInstructions(
        index + 1,
        """
            invoke-static { v$register }, $EXTENSION_CLASS->$methodName(Z)Z
            move-result v$register
        """
    )
}
