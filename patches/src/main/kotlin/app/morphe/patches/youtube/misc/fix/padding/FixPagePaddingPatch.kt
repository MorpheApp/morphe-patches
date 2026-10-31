/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.misc.fix.padding

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.matchAllMethodIndicesForEach
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playservice.is_21_07_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.indexOfFirstInstructionReversedOrThrow
import app.morphe.util.insertLiteralOverride
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21t
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/FixPagePaddingPatch;"

internal val fixPagePaddingPatch = bytecodePatch(
    description = "Fixes layout padding glitches in incognito search and watch history."
) {
    dependsOn(
        sharedExtensionPatch,
        versionCheckPatch
    )
    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        if (is_21_07_or_greater) {
            IncognitoSearchPaddingFeatureFlagFingerprint.matchAllMethodIndicesForEach(requireMatches = false) { index ->
                insertLiteralOverride(index, false)
            }
        }

        // With the edge-to-edge/translucent navigation bar, the keyboard shrinks the layout that holds
        // the page and the navigation bar to the top of the keyboard, so the navigation bar is moved above
        // the keyboard. The keyboard height is replaced with no margin (0), so the navigation bar stays
        // at the bottom behind the keyboard, except while the legacy miniplayer is shown.
        PageBottomMarginFingerprint.let {
            it.method.apply {
                val insetIndex = it.instructionMatches.last().index
                val marginRegister = getInstruction<TwoRegisterInstruction>(insetIndex).registerA
                val keyboardBranchIndex = indexOfFirstInstructionReversedOrThrow(insetIndex) {
                    opcode == Opcode.IF_EQZ &&
                            (this as BuilderInstruction21t).target.location.index == insetIndex
                }
                val keyboardRegister = getInstruction<OneRegisterInstruction>(keyboardBranchIndex).registerA

                addInstructionsAtControlFlowLabel(
                    insetIndex + 1,
                    """
                        if-eqz v$keyboardRegister, :keyboard_hidden
                        invoke-static/range { v$marginRegister .. v$marginRegister }, $EXTENSION_CLASS->getKeyboardMargin(I)I
                        move-result v$marginRegister
                        :keyboard_hidden
                        nop
                    """
                )
            }
        }

        FeedListPaddingFingerprint.let {
            it.method.apply {
                val index = it.instructionMatches.last().index
                val paddingInstruction = getInstruction<FiveRegisterInstruction>(index)
                val listRegister = paddingInstruction.registerC
                val bottomPaddingRegister = paddingInstruction.registerG

                addInstructions(
                    index,
                    """
                        invoke-static { v$listRegister, v$bottomPaddingRegister }, $EXTENSION_CLASS->getAdjustedFeedListBottomPadding(Landroid/view/View;I)I
                        move-result v$bottomPaddingRegister
                    """
                )
            }
        }

        BrowseListBottomPaddingFingerprint.let {
            it.method.apply {
                val index = it.instructionMatches.last().index
                val paddingInstruction = getInstruction<FiveRegisterInstruction>(index)
                val listRegister = paddingInstruction.registerC
                val bottomPaddingRegister = paddingInstruction.registerG

                addInstructions(
                    index,
                    """
                        invoke-static { v$listRegister, v$bottomPaddingRegister }, $EXTENSION_CLASS->getAdjustedFeedListBottomPadding(Landroid/view/View;I)I
                        move-result v$bottomPaddingRegister
                    """
                )
            }
        }
    }
}
