/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5192
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.modern

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.findFreeRegister
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
    "Lapp/morphe/extension/reddit/patches/HomeFeedTabsPatch;"

@Suppress("unused")
val homeFeedTabsPatch = patch(
    name = "Home feed tabs",
    description = "Adds an option to show the For You, Following and Popular tabs on the home feed, " +
            "and to switch them by swiping. This patch works with Reddit 2026.43.0 and newer."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(settingsPatch, versionCheckPatch)

    execute {
        if (!is_2026_43_0_or_greater) {
            return@execute Logger.getLogger(this::class.java.name).warning(
                "'Home feed tabs' does not work with Reddit 2026.42.0 and earlier"
            )
        }

        // Users who are not in the experiment have no variant, and see no tabs.
        // Use the For You name variant, which shows the tabs and names the first tab For You.
        // "Disable modern home" can hook the same method, so match it again.
        ForYouFollowingVariantFingerprint.let {
            it.clearMatch()
            val index = it.instructionMatches.first().index
            val register = it.method.getInstruction<OneRegisterInstruction>(index).registerA
            val freeRegister = it.method.findFreeRegister(index + 1, register)

            it.method.addInstructionsWithLabels(
                index + 1,
                """
                    invoke-static { }, $EXTENSION_CLASS->showHomeFeedTabs()Z
                    move-result v$freeRegister
                    if-eqz v$freeRegister, :tabs_off
                    sget-object v$register, $FOR_YOU_FOLLOWING_VARIANT_CLASS->FOR_YOU_NAME:$FOR_YOU_FOLLOWING_VARIANT_CLASS
                    :tabs_off
                    nop
                """
            )
        }

        // Show the Popular tab without its experiment flag, and also to signed out users.
        PopularHomeTabFingerprint.method.addInstructionsWithLabels(
            0,
            """
                invoke-static { }, $EXTENSION_CLASS->showHomeFeedTabs()Z
                move-result v0
                if-eqz v0, :tabs_off
                return v0
                :tabs_off
                nop
            """
        )

        PagerSwipeEnabledFingerprint.method.apply {
            findInstructionIndicesReversedOrThrow(Opcode.RETURN).forEach { index ->
                val register = getInstruction<OneRegisterInstruction>(index).registerA

                addInstructionsAtControlFlowLabel(
                    index,
                    """
                        invoke-static { v$register }, $EXTENSION_CLASS->enableTabSwipe(Z)Z
                        move-result v$register
                    """
                )
            }
        }

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}
