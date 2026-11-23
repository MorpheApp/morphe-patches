/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5244
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.misc.autorefresh

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.setExtensionIsPatchIncluded
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/reddit/patches/DisableHomeFeedAutoRefreshPatch;"

@Suppress("unused")
val disableHomeFeedAutoRefreshPatch = patch(
    name = "Disable home feed auto-refresh",
    description = "Adds an option to stop the home feed from reloading and jumping to the top when you return to the app."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(settingsPatch)

    execute {
        AutoRefreshFeedFingerprint.let {
            it.method.apply {
                val newEvent = it.instructionMatches.first()
                val sendEvent = it.instructionMatches.last()
                // The new-instance overwrites its register, so it is free before it.
                val freeRegister = newEvent.getInstruction<OneRegisterInstruction>().registerA

                addInstructionsWithLabels(
                    newEvent.index,
                    """
                        invoke-static { }, $EXTENSION_CLASS->skipAutoRefresh()Z
                        move-result v$freeRegister
                        if-nez v$freeRegister, :skip_auto_refresh
                    """,
                    ExternalLabel("skip_auto_refresh", getInstruction(sendEvent.index + 1))
                )
            }
        }

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}
