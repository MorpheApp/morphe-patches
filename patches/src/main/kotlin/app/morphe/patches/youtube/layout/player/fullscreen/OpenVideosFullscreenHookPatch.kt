/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.layout.player.fullscreen

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.addPatchInterface
import app.morphe.patches.youtube.layout.shortsplayer.openShortsInRegularPlayerPatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.util.insertLiteralOverride
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/OpenVideosFullscreenHookPatch;"

private const val EXTENSION_FULLSCREEN_INTERFACE =
    $$"Lapp/morphe/extension/youtube/patches/OpenVideosFullscreenHookPatch$FullscreenInterface;"

/**
 * Used by [openVideosFullscreenPatch], [openShortsInRegularPlayerPatch] and [exitFullscreenPatch].
 */
internal val openVideosFullscreenHookPatch = patch {
    dependsOn(
        sharedExtensionPatch
    )

    execute {
        val fullScreenMethod = AdPlayerFullscreenFingerprint.instructionMatches
            .last().getMethodCalled()
        val fullScreenDefiningClass = fullScreenMethod.definingClass

        // Implement fullscreen interface.
        mutableClassDefBy(fullScreenDefiningClass).apply {
            fun addInterfaceMethod(name: String, method: MethodReference) = addPatchInterface(
                EXTENSION_FULLSCREEN_INTERFACE,
                name,
                method
            )

            addInterfaceMethod("patch_exitFullscreen", fullScreenMethod)

            val enterFullscreenMethod = EnterFullscreenClickFingerprint.instructionMatches[1].getMethodCalled()

            addInterfaceMethod("patch_enterFullscreen", enterFullscreenMethod)
        }

        // Pass the fullscreen interface object to extension code.
        NextGenWatchLayoutConstructorFingerprint.let {
            it.method.apply {
                val index = it.instructionMatches.first().index
                val register = getInstruction<OneRegisterInstruction>(index).registerA

                addInstruction(
                    index + 1,
                    "invoke-static { v$register }, $EXTENSION_CLASS->setFullscreenInterface($EXTENSION_FULLSCREEN_INTERFACE)V"
                )
            }
        }

        OpenVideosFullscreenPortraitFingerprint.let {
            // Remove A/B feature call that forces what this patch already does.
            // Cannot use the A/B flag to accomplish the same goal because 19.50+
            // Shorts fullscreen regular player does not use fullscreen
            // if the player is minimized, and it must be forced using other conditional check.
            it.method.insertLiteralOverride(
                it.instructionMatches.last().index,
                false
            )
        }

        OpenVideosFullscreenPortraitFingerprint.let {
            it.method.apply {
                val index = it.instructionMatches.first().index
                val register = getInstruction<OneRegisterInstruction>(index).registerA

                addInstructions(
                    index + 1,
                    """
                        invoke-static { v$register }, $EXTENSION_CLASS->doNotOpenVideoFullscreenPortrait(Z)Z
                        move-result v$register
                    """
                )
            }
        }
    }
}
