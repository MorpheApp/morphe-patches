/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.video.playerresponse

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playservice.is_19_23_or_greater
import app.morphe.patches.youtube.misc.playservice.is_20_02_or_greater
import app.morphe.patches.youtube.misc.playservice.is_20_10_or_greater
import app.morphe.patches.youtube.misc.playservice.is_20_15_or_greater
import app.morphe.patches.youtube.misc.playservice.is_20_26_or_greater
import app.morphe.patches.youtube.misc.playservice.is_20_46_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import java.lang.ref.WeakReference

private val hooks = mutableSetOf<Hook>()

fun addPlayerResponseMethodHook(hook: Hook) {
    hooks += hook
}

// Parameter numbers of the patched method.
private const val PARAMETER_VIDEO_ID = 1
private const val PARAMETER_PROTO_BUFFER = 3
private const val PARAMETER_PLAYLIST_ID = 4
private var parameterIsShortAndOpeningOrPlaying = -1

// Registers used to pass the parameters to the extension.
private var playerResponseMethodCopyRegisters = false
private lateinit var registerVideoId: String
private lateinit var registerProtoBuffer: String
private lateinit var registerPlaylistId: String
private lateinit var registerIsShortAndOpeningOrPlaying: String

private lateinit var playerResponseMethodRef : WeakReference<MutableMethod>
private var numberOfInstructionsAdded = 0

val playerResponseMethodHookPatch = bytecodePatch {
    dependsOn(
        sharedExtensionPatch,
        versionCheckPatch,
    )

    execute {
        val fingerprint : Fingerprint
        if (is_20_46_or_greater) {
            parameterIsShortAndOpeningOrPlaying = 13
            fingerprint = PlayerParameterBuilderFingerprint
        } else if (is_20_26_or_greater) {
            parameterIsShortAndOpeningOrPlaying = 13
            fingerprint = PlayerParameterBuilder2026Fingerprint
        } else if (is_20_15_or_greater) {
            parameterIsShortAndOpeningOrPlaying = 13
            fingerprint = PlayerParameterBuilder2015Fingerprint
        } else if (is_20_10_or_greater) {
            parameterIsShortAndOpeningOrPlaying = 13
            fingerprint = PlayerParameterBuilder2010Fingerprint
        } else if (is_20_02_or_greater) {
            parameterIsShortAndOpeningOrPlaying = 12
            fingerprint = PlayerParameterBuilder2002Fingerprint
        } else if (is_19_23_or_greater) {
            parameterIsShortAndOpeningOrPlaying = 12
            fingerprint = PlayerParameterBuilder1925Fingerprint
        } else {
            parameterIsShortAndOpeningOrPlaying = 11
            fingerprint = PlayerParameterBuilderLegacyFingerprint
        }
        val playerResponseMethod = fingerprint.method
        playerResponseMethodRef = WeakReference(playerResponseMethod)

        // On some app targets the method has too many registers pushing the parameters past v15.
        // If needed, move the parameters to 4-bit registers, so they can be passed to the extension.
        playerResponseMethodCopyRegisters = playerResponseMethod.implementation!!.registerCount -
            playerResponseMethod.parameterTypes.size + parameterIsShortAndOpeningOrPlaying > 15

        if (playerResponseMethodCopyRegisters) {
            registerVideoId = "v0"
            registerProtoBuffer = "v1"
            registerPlaylistId = "v2"
            registerIsShortAndOpeningOrPlaying = "v3"
        } else {
            registerVideoId = "p$PARAMETER_VIDEO_ID"
            registerProtoBuffer = "p$PARAMETER_PROTO_BUFFER"
            registerPlaylistId = "p$PARAMETER_PLAYLIST_ID"
            registerIsShortAndOpeningOrPlaying = "p$parameterIsShortAndOpeningOrPlaying"
        }
    }

    finalize {
        fun hookPlaylistId(hook: Hook) {
            playerResponseMethodRef.get()!!.addInstruction(
                0,
                "invoke-static { $registerPlaylistId, $registerIsShortAndOpeningOrPlaying }, $hook",
            )
            numberOfInstructionsAdded++
        }

        fun hookVideoId(hook: Hook) {
            playerResponseMethodRef.get()!!.addInstruction(
                0,
                "invoke-static { $registerVideoId, $registerIsShortAndOpeningOrPlaying }, $hook",
            )
            numberOfInstructionsAdded++
        }

        fun hookProtoBufferParameter(hook: Hook) {
            playerResponseMethodRef.get()!!.addInstructions(
                0,
                """
                    invoke-static { $registerProtoBuffer, $registerVideoId, $registerIsShortAndOpeningOrPlaying }, $hook
                    move-result-object $registerProtoBuffer
                """
            )
            numberOfInstructionsAdded += 2
        }

        // Reverse the order in order to preserve insertion order of the hooks.
        val beforeVideoIdHooks = hooks.filterIsInstance<Hook.ProtoBufferParameterBeforeVideoId>().asReversed()
        val playlistIdHooks = hooks.filterIsInstance<Hook.PlaylistId>().asReversed()
        val videoIdHooks = hooks.filterIsInstance<Hook.VideoId>().asReversed()
        val afterVideoIdHooks = hooks.filterIsInstance<Hook.ProtoBufferParameter>().asReversed()

        // Add the hooks in this specific order as they insert instructions at the beginning of the method.
        afterVideoIdHooks.forEach(::hookProtoBufferParameter)
        playlistIdHooks.forEach(::hookPlaylistId)
        videoIdHooks.forEach(::hookVideoId)
        beforeVideoIdHooks.forEach(::hookProtoBufferParameter)

        if (playerResponseMethodCopyRegisters) {
            playerResponseMethodRef.get()!!.addInstructions(
                0,
                """
                    move-object/from16 $registerVideoId, p$PARAMETER_VIDEO_ID
                    move-object/from16 $registerProtoBuffer, p$PARAMETER_PROTO_BUFFER
                    move-object/from16 $registerPlaylistId, p$PARAMETER_PLAYLIST_ID
                    move/from16        $registerIsShortAndOpeningOrPlaying, p$parameterIsShortAndOpeningOrPlaying
                """
            )
            numberOfInstructionsAdded += 4

            // Move the modified register back.
            playerResponseMethodRef.get()!!.addInstruction(
                numberOfInstructionsAdded,
                "move-object/from16 p$PARAMETER_PROTO_BUFFER, $registerProtoBuffer",
            )
        }

        hooks.clear()
    }
}

sealed class Hook(private val methodDescriptor: String) {
    class PlaylistId(methodDescriptor: String) : Hook(methodDescriptor)
    class VideoId(methodDescriptor: String) : Hook(methodDescriptor)

    class ProtoBufferParameter(methodDescriptor: String) : Hook(methodDescriptor)
    class ProtoBufferParameterBeforeVideoId(methodDescriptor: String) : Hook(methodDescriptor)

    override fun toString() = methodDescriptor
}
