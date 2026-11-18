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

        // Hooked after the call that scrolls the feed to the top, not in the called method,
        // because the call goes through an interface with several identical implementations.
        // Must be done before the code added at the start of the same method below.
        RecyclerViewTopScrollingCallingMethodFingerprint.let {
            it.method.addInstruction(
                it.instructionMatches.first().index + 1,
                "invoke-static { }, $EXTENSION_CLASS->onTopView()V"
            )
        }

        if (is_21_45_or_greater) {
            // The feature flag is gone and the feed always scrolls to the top. Back is handled by
            // one of two navigation controllers, and both ask the feed through this method, which
            // does the scrolling. Returning false skips it, and back then exits as usual.
            RecyclerViewTopScrollingCallingMethodFingerprint.method.apply {
                val free = findFreeRegister(0)

                addInstructionsWithLabels(
                    0,
                    """
                        const/4 v$free, 0x1
                        invoke-static { v$free }, $EXTENSION_CLASS->allowBackButtonToScrollToTopOfFeed(Z)Z
                        move-result v$free
                        if-nez v$free, :scroll_to_top
                        return v$free
                    """,
                    ExternalLabel("scroll_to_top", getInstruction(0))
                )
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
