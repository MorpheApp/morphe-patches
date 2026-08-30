/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2556
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.video.playerresponse

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.playservice.versionCheckPatch
import java.lang.ref.WeakReference

private val hooks = mutableSetOf<Hook>()

fun addPlayerResponseMethodHook(hook: Hook) {
    hooks += hook
}

private const val REGISTER_VIDEO_ID = "p1"
private const val REGISTER_PLAYER_PARAMETER = "p3"
private const val REGISTER_PLAYLIST_ID = "p4"
private const val REGISTER_PLAYLIST_INDEX = "p5"

private lateinit var playerResponseMethodRef: WeakReference<MutableMethod>
private var numberOfInstructionsAdded = 0

val musicPlayerResponseMethodHookPatch = bytecodePatch(
    description = "Hooks the YouTube Music player parameter builder to expose video id, playlist id and player parameter."
) {
    dependsOn(
        sharedExtensionPatch,
        versionCheckPatch
    )

    execute {
        playerResponseMethodRef = WeakReference(PlayerParameterBuilderFingerprint.method)
    }

    finalize {
        val playerResponseMethod = playerResponseMethodRef.get()!!

        fun hookVideoId(hook: Hook) {
            playerResponseMethod.addInstruction(
                0,
                "invoke-static { $REGISTER_VIDEO_ID }, $hook"
            )
            numberOfInstructionsAdded++
        }

        fun hookVideoIdAndPlaylistId(hook: Hook) {
            playerResponseMethod.addInstruction(
                0,
                "invoke-static { $REGISTER_VIDEO_ID, $REGISTER_PLAYLIST_ID, $REGISTER_PLAYLIST_INDEX }, $hook"
            )
            numberOfInstructionsAdded++
        }

        fun hookPlayerParameter(hook: Hook) {
            playerResponseMethod.addInstructions(
                0,
                """
                    invoke-static { $REGISTER_VIDEO_ID, v0 }, $hook
                    move-result-object v0
                """
            )
            numberOfInstructionsAdded += 2
        }

        // Reverse the order in order to preserve insertion order of the hooks.
        val beforeVideoIdHooks = hooks.filterIsInstance<Hook.ProtoBufferParameterBeforeVideoId>().asReversed()
        val videoIdHooks = hooks.filterIsInstance<Hook.VideoId>().asReversed()
        val videoIdAndPlaylistIdHooks = hooks.filterIsInstance<Hook.VideoIdAndPlaylistId>().asReversed()
        val afterVideoIdHooks = hooks.filterIsInstance<Hook.PlayerParameter>().asReversed()

        // Add the hooks in this specific order as they insert instructions at the beginning of the method.
        afterVideoIdHooks.forEach(::hookPlayerParameter)
        videoIdAndPlaylistIdHooks.forEach(::hookVideoIdAndPlaylistId)
        videoIdHooks.forEach(::hookVideoId)
        beforeVideoIdHooks.forEach(::hookPlayerParameter)

        if (afterVideoIdHooks.isNotEmpty() || beforeVideoIdHooks.isNotEmpty()) {
            // Stash the player parameter into v0 so PlayerParameter hooks can read and replace it.
            playerResponseMethod.addInstruction(
                0,
                "move-object/from16 v0, $REGISTER_PLAYER_PARAMETER"
            )
            numberOfInstructionsAdded++

            // Move the modified register back.
            playerResponseMethod.addInstruction(
                numberOfInstructionsAdded,
                "move-object/from16 $REGISTER_PLAYER_PARAMETER, v0"
            )
        }

        hooks.clear()
    }
}

sealed class Hook(private val methodDescriptor: String) {
    class VideoId(methodDescriptor: String) : Hook(methodDescriptor)
    class VideoIdAndPlaylistId(methodDescriptor: String) : Hook(methodDescriptor)

    class PlayerParameter(methodDescriptor: String) : Hook(methodDescriptor)
    class ProtoBufferParameterBeforeVideoId(methodDescriptor: String) : Hook(methodDescriptor)

    override fun toString() = methodDescriptor
}
