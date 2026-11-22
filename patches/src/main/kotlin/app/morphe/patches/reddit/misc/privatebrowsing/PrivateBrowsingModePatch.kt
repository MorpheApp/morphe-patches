/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5161
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.misc.privatebrowsing

import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.patch
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.misc.version.is_2026_43_0_or_greater
import app.morphe.patches.reddit.misc.version.versionCheckPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.findInstructionIndicesReversedOrThrow
import app.morphe.util.setExtensionIsPatchIncluded
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import java.util.logging.Logger

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/reddit/patches/PrivateBrowsingModePatch;"

@Suppress("unused")
val privateBrowsingModePatch = patch(
    name = "Private browsing mode",
    description = "Adds an option to choose whether private browsing uses Anonymous Browsing or Incognito. " +
            "This patch works with Reddit 2026.43.0 and newer."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(settingsPatch, versionCheckPatch)

    execute {
        if (!is_2026_43_0_or_greater) {
            return@execute Logger.getLogger(this::class.java.name).warning(
                "'Private browsing mode' requires Reddit 2026.43.0+"
            )
        }

        IncognitoRevampEnabledFingerprint.method.apply {
            findInstructionIndicesReversedOrThrow(Opcode.RETURN).forEach { index ->
                val register = getInstruction<OneRegisterInstruction>(index).registerA

                addInstructionsAtControlFlowLabel(
                    index,
                    """
                        invoke-static { v$register }, $EXTENSION_CLASS->isIncognitoEnabled(Z)Z
                        move-result v$register
                    """
                )
            }
        }

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}
