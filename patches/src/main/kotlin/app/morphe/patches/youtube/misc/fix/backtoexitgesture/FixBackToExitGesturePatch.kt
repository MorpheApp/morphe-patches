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
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playertype.playerTypeHookPatch
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstructionOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/FixBackToExitGesturePatch;"

internal val fixBackToExitGesturePatch = bytecodePatch(
    description = "Fixes the swipe back to exit gesture."
) {
    dependsOn(
        sharedExtensionPatch,
        playerTypeHookPatch,
        versionCheckPatch
    )

    execute {
        RecyclerViewTopScrollingFingerprint.let {
            it.method.addInstructionsAtControlFlowLabel(
                it.instructionMatches.last().index + 1,
                "invoke-static { }, $EXTENSION_CLASS->onTopView()V"
            )
        }

        ScrollPositionFingerprint.instructionMatches[1].getMethodCalled().apply {
            val index = indexOfFirstInstructionOrThrow {
                opcode == Opcode.INVOKE_VIRTUAL && getReference<MethodReference>()?.definingClass ==
                        "Landroid/support/v7/widget/RecyclerView;"
            }

            addInstruction(
                index,
                "invoke-static { }, $EXTENSION_CLASS->onScrollingViews()V"
            )
        }

        BackToRefreshFeatureFlagFingerprint.matchAll().forEach {
            val smali =
                """
                    invoke-static {}, $EXTENSION_CLASS->shouldInterceptBackPress()Z
                    move-result v0

                    if-nez v0, :cond_continue_native

                    const/4 v0, 1
                    return v0

                    :cond_continue_native
                """.trimIndent()

            it.method.addInstruction(0, smali)
        }
    }
}
