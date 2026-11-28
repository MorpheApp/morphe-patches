/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.misc.loadvideo

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.addPatchInterface
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.returnEarly
import app.morphe.patches.youtube.misc.commandrouter.commandRouterHookPatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.util.indexOfFirstInstructionReversedOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.util.MethodUtil

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/LoadVideoPatch;"

private const val EXTENSION_PLAYER_INTERFACE =
    $$"Lapp/morphe/extension/youtube/patches/LoadVideoPatch$PlayerInterface;"

private const val EXTENSION_INLINE_PLAYBACK_INTERFACE =
    $$"Lapp/morphe/extension/youtube/patches/LoadVideoPatch$InlinePlaybackInterface;"

internal val loadVideoHookPatch = patch(
    description = "Hooks the player so the extension can dismiss it and open videos."
) {
    dependsOn(
        sharedExtensionPatch,
        commandRouterHookPatch,
    )

    execute {
        val dismissPlayerInnerMethod = MiniAppOpenYtContentCommandEndpointFingerprint
            .instructionMatches.last()
            .getInstruction<ReferenceInstruction>()
            .getReference<MethodReference>()!!

        mutableClassDefBy(dismissPlayerInnerMethod.definingClass).apply {
            // Add interface and helper methods to allow extension code to call obfuscated methods.
            // Add methods to access obfuscated player methods.
            addPatchInterface(
                EXTENSION_PLAYER_INTERFACE,
                "patch_dismissPlayer",
                dismissPlayerInnerMethod
            )

            methods.single { method ->
                MethodUtil.isConstructor(method)
            }.apply {
                val index = indexOfFirstInstructionReversedOrThrow(Opcode.RETURN_VOID)

                addInstruction(
                    index,
                    "invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS->initialize($EXTENSION_PLAYER_INTERFACE)V"
                )
            }
        }

        // Once the player is dismissed, a video in the feed below it can start playing
        // and take the place of the video opened next, so the extension blocks it.
        val blockInlinePlaybackMethod = BlockInlinePlaybackFingerprint.method
        mutableClassDefBy(blockInlinePlaybackMethod.definingClass).apply {
            val unblockInlinePlaybackMethod = UnblockInlinePlaybackFingerprint.match(this).method

            mapOf(
                "patch_blockInlinePlayback" to blockInlinePlaybackMethod,
                "patch_unblockInlinePlayback" to unblockInlinePlaybackMethod
            ).forEach { (name, method) ->
                addPatchInterface(
                    EXTENSION_INLINE_PLAYBACK_INTERFACE,
                    name,
                    method
                )
            }

            methods.single { method ->
                MethodUtil.isConstructor(method)
            }.apply {
                val index = indexOfFirstInstructionReversedOrThrow(Opcode.RETURN_VOID)

                addInstruction(
                    index,
                    "invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS->" +
                            "initializeInlinePlayback($EXTENSION_INLINE_PLAYBACK_INTERFACE)V"
                )
            }
        }

        BackButtonFinishActivityOnNewVideoIntentFingerprint.method.returnEarly()
    }
}
