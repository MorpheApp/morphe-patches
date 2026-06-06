/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.layout.player.fullscreen

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.checkCast
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.youtube.layout.shortsplayer.openShortsInRegularPlayerPatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.util.insertLiteralOverride
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod

internal const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/OpenVideosFullscreenHookPatch;"

private const val EXTENSION_FULLSCREEN_INTERFACE =
    $$"Lapp/morphe/extension/youtube/patches/OpenVideosFullscreenHookPatch$FullscreenInterface;"

/**
 * Used by both [openVideosFullscreenPatch] and [openShortsInRegularPlayerPatch].
 */
internal val openVideosFullscreenHookPatch = bytecodePatch {
    dependsOn(
        sharedExtensionPatch
    )

    execute {
        val exitFullscreenMethod = AdPlayerFullscreenFingerprint.instructionMatches.last().getMethodCalled()

        // Implement fullscreen interface.
        mutableClassDefBy(exitFullscreenMethod.definingClass).apply {
            interfaces.add(EXTENSION_FULLSCREEN_INTERFACE)
            methods.add(
                ImmutableMethod(
                    type,
                    "patch_exitFullscreen",
                    listOf(),
                    "V",
                    AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
                    null,
                    null,
                    MutableMethodImplementation(1),
                ).toMutable().apply {
                    addInstructions(
                        0,
                        """
                            invoke-virtual { p0 }, $exitFullscreenMethod
                            return-void
                        """
                    )
                }
            )
        }

        // Pass the fullscreen interface object to extension code.
        Fingerprint(
            definingClass = "Lcom/google/android/apps/youtube/app/watch/nextgenwatch/ui/NextGenWatchLayout;",
            accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
            filters = listOf(
                checkCast(exitFullscreenMethod.definingClass)
            )
        ).let {
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
