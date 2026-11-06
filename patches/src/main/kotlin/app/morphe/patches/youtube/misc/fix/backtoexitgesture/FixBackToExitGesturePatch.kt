/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.misc.fix.backtoexitgesture

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.findFreeRegister
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.backgesture.addBackPressedHook
import app.morphe.patches.youtube.misc.backgesture.addPredictiveBackGestureHook
import app.morphe.patches.youtube.misc.backgesture.backGesturePatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playertype.playerTypeHookPatch
import app.morphe.patches.youtube.misc.playservice.is_21_45_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.indexOfFirstInstructionOrThrow
import app.morphe.util.insertLiteralOverride
import com.android.tools.smali.dexlib2.Opcode

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/FixBackToExitGesturePatch;"

internal val fixBackToExitGesturePatch = patch(
    description = "Fixes the swipe back to exit gesture."
) {
    dependsOn(
        sharedExtensionPatch,
        playerTypeHookPatch,
        backGesturePatch,
        versionCheckPatch,
        settingsPatch,
    )

    execute {
        PreferenceScreen.MISC.addPreferences(
            SwitchPreference("morphe_back_button_always_exits_feed", summary = true)
        )

        // FIXME? Originally this only changed the first match, but this now matches
        //        up to 3 identical methods in different classes (21.45+ has 3 tab controllers).
        recyclerViewTopScrollingFingerprint().matchAll(1..3).forEach {
            it.method.addInstructionsAtControlFlowLabel(
                it.instructionMatches.last().index + 1,
                "invoke-static { }, $EXTENSION_CLASS->onTopView()V"
            )
        }

        if (is_21_45_or_greater) {
            // The feature flag is gone and the feed always scrolls to the top. The called method
            // does the scrolling, so it must be skipped instead of changing what it returns.
            BackToRefreshFingerprint.let {
                it.method.apply {
                    val index = it.instructionMatches[1].index
                    val free = findFreeRegister(index)

                    addInstructionsWithLabels(
                        index,
                        """
                            const/4 v$free, 0x1
                            invoke-static { v$free }, $EXTENSION_CLASS->allowBackButtonToScrollToTopOfFeed(Z)Z
                            move-result v$free
                            if-eqz v$free, :do_not_scroll
                        """,
                        ExternalLabel("do_not_scroll", getInstruction(it.instructionMatches.last().index + 1))
                    )
                }
            }
        } else {
            BackToRefreshFeatureFlagFingerprint.matchAll().forEach {
                it.method.insertLiteralOverride(
                    it.instructionMatches.first().index,
                    "$EXTENSION_CLASS->allowBackButtonToScrollToTopOfFeed(Z)Z"
                )
            }
        }

        ScrollPositionFingerprint.instructionMatches[1].getMethodCalled().apply {
            val index = indexOfFirstInstructionOrThrow(
                methodCall(
                    opcode = Opcode.INVOKE_VIRTUAL,
                    definingClass = "Landroid/support/v7/widget/RecyclerView;"
                )
            )

            addInstruction(
                index,
                "invoke-static { }, $EXTENSION_CLASS->onScrollingViews()V"
            )
        }

        addBackPressedHook(EXTENSION_CLASS, "onBackInvoked", afterActivityBackPressed = true)
        addPredictiveBackGestureHook(EXTENSION_CLASS)
    }
}
