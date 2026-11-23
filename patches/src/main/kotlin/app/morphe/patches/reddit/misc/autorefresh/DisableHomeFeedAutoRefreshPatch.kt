/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.misc.autorefresh

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.registersUsed
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.misc.version.is_2026_40_0_or_greater
import app.morphe.patches.reddit.misc.version.versionCheckPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.setExtensionIsPatchIncluded
import java.util.logging.Logger

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/reddit/patches/DisableHomeFeedAutoRefreshPatch;"

@Suppress("unused")
val disableHomeFeedAutoRefreshPatch = patch(
    name = "Disable home feed auto-refresh",
    description = "Adds an option to stop the home feed from reloading and jumping to the top when you return to the app. " +
        "This patch works with Reddit 2026.40.0 and newer."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(settingsPatch, versionCheckPatch)

    execute {
        if (!is_2026_40_0_or_greater) {
            return@execute Logger.getLogger(this::class.java.name).warning(
                "'Disable home feed auto-refresh' does not work with Reddit 2026.39.0 and earlier"
            )
        }

        AutoRefreshFeedFingerprint.let {
            it.method.apply {
                val newIndex = it.instructionMatches[0].index
                val sendIndex = it.instructionMatches[2].index
                val register = getInstruction(newIndex).registersUsed[0]
                val skipTarget = getInstruction(sendIndex + 1)

                addInstructionsWithLabels(
                    newIndex,
                    """
                        invoke-static { }, $EXTENSION_CLASS->skipAutoRefresh()Z
                        move-result v$register
                        if-nez v$register, :skip_auto_refresh
                    """,
                    ExternalLabel("skip_auto_refresh", skipTarget)
                )
            }
        }

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}
