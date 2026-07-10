/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.layout.player.fullscreen

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.checkCast
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.youtube.layout.shortsplayer.openShortsInRegularPlayerPatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.util.insertLiteralOverride
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
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
        val fullScreenMethod = AdPlayerFullscreenFingerprint.instructionMatches
            .last().getMethodCalled()
        val fullScreenDefiningClass = fullScreenMethod.definingClass

        // Implement fullscreen interface.
        mutableClassDefBy(fullScreenDefiningClass).apply {
            interfaces.add(EXTENSION_FULLSCREEN_INTERFACE)

            fun addInterfaceMethod(name: String, methodCall: String) = methods.add(
                ImmutableMethod(
                    type,
                    name,
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
                            invoke-virtual { p0 }, $methodCall
                            return-void
                        """
                    )
                }
            )

            addInterfaceMethod("patch_exitFullscreen", "$fullScreenMethod")

            val enterFullscreenMethod = Fingerprint(
                name = "onClick",
                returnType = "V",
                parameters = listOf("Landroid/view/View;"),
                filters = listOf(
                    fieldAccess(
                        opcode = Opcode.IGET_OBJECT,
                        type = fullScreenDefiningClass
                    ),
                    methodCall(
                        opcode = Opcode.INVOKE_VIRTUAL,
                        definingClass = fullScreenDefiningClass,
                        returnType = "V",
                        parameters = listOf(),
                        location = MatchAfterWithin(3)
                    ),
                    methodCall(
                        opcode = Opcode.INVOKE_VIRTUAL,
                        smali = fullScreenMethod.toString(),
                        location = MatchAfterWithin(10)
                    )
                )
            ).instructionMatches[1].getMethodCalled()

            addInterfaceMethod("patch_enterFullscreen", "$enterFullscreenMethod")
        }

        // Pass the fullscreen interface object to extension code.
        Fingerprint(
            definingClass = "Lcom/google/android/apps/youtube/app/watch/nextgenwatch/ui/NextGenWatchLayout;",
            accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
            filters = listOf(
                checkCast(fullScreenDefiningClass)
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
