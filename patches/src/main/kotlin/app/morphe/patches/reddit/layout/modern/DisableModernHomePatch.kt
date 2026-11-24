/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.modern

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.matchAllMethodIndicesForEach
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.misc.version.is_2026_25_0_or_greater
import app.morphe.patches.reddit.misc.version.is_2026_40_0_or_greater
import app.morphe.patches.reddit.misc.version.is_2026_45_0_or_greater
import app.morphe.patches.reddit.misc.version.versionCheckPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.findInstructionIndicesReversedOrThrow
import app.morphe.util.setExtensionIsPatchIncluded
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import java.util.logging.Logger

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/reddit/patches/DisableModernHomePatch;"

@Suppress("unused")
val disableModernHomePatch = patch(
    name = "Disable modern home",
    description = "Adds an option to disable the modern home UI. " +
            "This patch works with Reddit 2026.24.0 and earlier, and 2026.40.0 and later."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(settingsPatch, versionCheckPatch)

    execute {
        if (!is_2026_25_0_or_greater) {
            HomeRevampVariantFingerprint.method.apply {
                findInstructionIndicesReversedOrThrow(Opcode.RETURN).forEach { index ->
                    val register = getInstruction<OneRegisterInstruction>(index).registerA

                    addInstructionsAtControlFlowLabel(
                        index,
                        """
                            invoke-static { v$register }, $EXTENSION_CLASS->disableModernHome(Z)Z
                            move-result v$register
                        """
                    )
                }
            }
        } else if (is_2026_40_0_or_greater) {
            // The For You and Following home, which replaced the modern home of 2026.24.0 and earlier.
            // Make the variant act as the control group, which returns false for every option
            // (isEnabled, isCenterFillLayoutEnabled, ...). Some app versions read the variant at each use.
            ForYouFollowingVariantOptionFingerprint.matchAllMethodIndicesForEach { index ->
                val register = getInstruction<OneRegisterInstruction>(index).registerA

                addInstructionsAtControlFlowLabel(
                    index,
                    """
                        invoke-static { v$register }, $EXTENSION_CLASS->disableModernHome(Z)Z
                        move-result v$register
                    """
                )
            }

            // The variant is read by one method, which is also used to compare the variant,
            // such as for the Popular tab. No variant is the state of a user who is not in the experiment.
            if (is_2026_45_0_or_greater) {
                ForYouFollowingVariantFingerprint.let {
                    val index = it.instructionMatches.first().index
                    val register = it.method.getInstruction<OneRegisterInstruction>(index).registerA

                    it.method.addInstructions(
                        index + 1,
                        """
                            invoke-static { v$register }, $EXTENSION_CLASS->disableForYouFollowing(Ljava/lang/Object;)Ljava/lang/Object;
                            move-result-object v$register
                            check-cast v$register, $FOR_YOU_FOLLOWING_VARIANT_CLASS
                        """
                    )
                }
            }
        } else {
            return@execute Logger.getLogger(this::class.java.name).warning(
                "'Disable modern home' does not work with Reddit 2026.25.0 - 2026.39.0"
            )
        }

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}
