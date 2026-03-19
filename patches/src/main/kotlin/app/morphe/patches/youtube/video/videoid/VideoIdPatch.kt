/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.video.videoid

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playertype.playerTypeHookPatch
import app.morphe.patches.youtube.video.playerresponse.Hook
import app.morphe.patches.youtube.video.playerresponse.addPlayerResponseMethodHook
import app.morphe.patches.youtube.video.playerresponse.playerResponseMethodHookPatch
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import java.lang.ref.WeakReference

/**
 * Hooks the new video ID when the video changes.
 *
 * Supports all videos (regular videos and Shorts).
 *
 * _Does not function if playing in the background with no video visible_.
 *
 * Be aware, this can be called multiple times for the same video ID.
 *
 * @param methodDescriptor which method to call. Params have to be `Ljava/lang/String;`
 */
fun hookVideoId(
    methodDescriptor: String,
) = videoIdMethodRef.get()!!.addInstruction(
    videoIdInsertIndex++,
    "invoke-static {v$videoIdRegister}, $methodDescriptor",
)

/**
 * Alternate hook that supports only regular videos, but hook supports changing to new video
 * during background play when no video is visible.
 *
 * _Does not support Shorts_.
 *
 * Be aware, the hook can be called multiple times for the same video ID.
 *
 * @param methodDescriptor which method to call. Params have to be `Ljava/lang/String;`
 */
fun hookBackgroundPlayVideoId(
    methodDescriptor: String,
) = backgroundPlaybackMethodRef.get()!!.addInstruction(
    backgroundPlaybackInsertIndex++, // move-result-object offset
    "invoke-static {v$backgroundPlaybackVideoIdRegister}, $methodDescriptor",
)

/**
 * Hooks the playlist ID of every video when loaded.
 * Supports all videos and functions in all situations.
 *
 * First parameter is the playlist ID.
 * Second parameter is if the video is a Short AND it is being opened or is currently playing.
 *
 * Hook is always called off the main thread.
 *
 * This hook is called as soon as the player response is parsed,
 * and called before many other hooks are updated such as [playerTypeHookPatch].
 *
 * Note: The playlist ID returned here may not be the current video that's being played.
 * It's common for multiple Shorts to load at once in preparation
 * for the user swiping to the next Short.
 *
 * Be aware, this can be called multiple times for the same playlist ID.
 *
 * @param methodDescriptor which method to call. Params must be `Ljava/lang/String;Z`
 */
fun hookPlayerResponsePlaylistId(methodDescriptor: String) = addPlayerResponseMethodHook(
    Hook.PlaylistId(
        methodDescriptor,
    ),
)

/**
 * Hooks the video ID of every video when loaded.
 * Supports all videos and functions in all situations.
 *
 * First parameter is the video ID.
 * Second parameter is if the video is a Short AND it is being opened or is currently playing.
 *
 * Hook is always called off the main thread.
 *
 * This hook is called as soon as the player response is parsed,
 * and called before many other hooks are updated such as [playerTypeHookPatch].
 *
 * Note: The video ID returned here may not be the current video that's being played.
 * It's common for multiple Shorts to load at once in preparation
 * for the user swiping to the next Short.
 *
 * For most use cases, you probably want to use
 * [hookVideoId] or [hookBackgroundPlayVideoId] instead.
 *
 * Be aware, this can be called multiple times for the same video ID.
 *
 * @param methodDescriptor which method to call. Params must be `Ljava/lang/String;Z`
 */
fun hookPlayerResponseVideoId(methodDescriptor: String) = addPlayerResponseMethodHook(
    Hook.VideoId(
        methodDescriptor,
    ),
)

private lateinit var videoIdMethodRef : WeakReference<MutableMethod>
private var videoIdRegister = -1
private var videoIdInsertIndex = -1

private lateinit var backgroundPlaybackMethodRef : WeakReference<MutableMethod>
private var backgroundPlaybackVideoIdRegister = -1
private var backgroundPlaybackInsertIndex = -1

val videoIdPatch = bytecodePatch(
    description = "Hooks to detect when the video ID changes.",
) {
    dependsOn(
        sharedExtensionPatch,
        playerResponseMethodHookPatch,
    )

    execute {
        VideoIdFingerprint.match(VideoIdParentFingerprint.originalClassDef).let {
            it.method.apply {
                videoIdMethodRef = WeakReference(this)
                val index = it.instructionMatches[1].index
                videoIdRegister = getInstruction<OneRegisterInstruction>(index).registerA
                videoIdInsertIndex = index + 1
            }
        }

        VideoIdBackgroundPlayFingerprint.let {
            it.method.apply {
                backgroundPlaybackMethodRef = WeakReference(this)
                val index = it.instructionMatches.first().index
                backgroundPlaybackVideoIdRegister = getInstruction<OneRegisterInstruction>(index + 1).registerA
                backgroundPlaybackInsertIndex = index + 2
            }
        }
    }
}