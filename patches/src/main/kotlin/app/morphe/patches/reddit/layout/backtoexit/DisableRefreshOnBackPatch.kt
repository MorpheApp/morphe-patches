/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5245
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.backtoexit

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.patch
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.misc.version.is_2026_24_0_or_greater
import app.morphe.patches.reddit.misc.version.versionCheckPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.setExtensionIsPatchIncluded
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import java.util.logging.Logger

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/reddit/patches/DisableRefreshOnBackPatch;"

@Suppress("unused")
val disableRefreshOnBackPatch = patch(
    name = "Disable refresh on back to exit",
    description = "Adds an option to exit the app with back on the home feed without first scrolling to the top " +
            "and reloading. This patch works with Reddit 2026.24.0 and newer."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(settingsPatch, versionCheckPatch)

    execute {
        // Back does not refresh the home feed in 2026.10.0.
        if (!is_2026_24_0_or_greater) {
            return@execute Logger.getLogger(this::class.java.name).warning(
                "'Disable refresh on back to exit' does not work with Reddit 2026.23.0 and earlier"
            )
        }

        HomeBackPressedFingerprint.let {
            it.method.apply {
                // The hint is a branch target, so jumps to it must also run the hook.
                val showHintIndex = it.instructionMatches[4].index
                val showHintRegister = getInstruction<OneRegisterInstruction>(showHintIndex).registerA

                addInstructionsAtControlFlowLabel(
                    showHintIndex,
                    """
                        invoke-static { v$showHintRegister }, $EXTENSION_CLASS->showExitHint(Z)Z
                        move-result v$showHintRegister
                    """
                )

                val refreshIndex = it.instructionMatches[2].index
                val refreshRegister = getInstruction<OneRegisterInstruction>(refreshIndex).registerA

                addInstructions(
                    refreshIndex + 1,
                    """
                        invoke-static { v$refreshRegister }, $EXTENSION_CLASS->refreshOnBack(Z)Z
                        move-result v$refreshRegister
                    """
                )
            }
        }

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}
