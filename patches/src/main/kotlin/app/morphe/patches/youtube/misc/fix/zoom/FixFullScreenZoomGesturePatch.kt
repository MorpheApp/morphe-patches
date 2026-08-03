/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for §7(c) terms that apply to this code.
 */

package app.morphe.patches.youtube.misc.fix.zoom

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/FixFullScreenZoomGesturePatch;"

@Suppress("unused")
internal val fixFullScreenZoomGesturePatch = bytecodePatch(
    description = "Forces off a flag that breaks fullscreen pinch to zoom."
) {
    dependsOn(
        sharedExtensionPatch,
        versionCheckPatch
    )

    execute {
        FullscreenGestureZoomFingerprint.apply {
            method.apply {
                val instructionIndex = instructionMatches[8].index
                val instructionRegister = getInstruction<OneRegisterInstruction>(
                    instructionIndex
                ).registerA

                addInstructions(
                    instructionIndex + 1,
                    """
                        invoke-static { v$instructionRegister }, $EXTENSION_CLASS->disableBrokenZoomFlag(Z)Z
                        move-result v$instructionRegister
                    """
                )
            }
        }
    }
}
