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
fun hookVideoID(
    methodDescriptor: String,
) = videoIDMethod.addInstruction(
    videoIDInsertIndex++,
    "invoke-static {v$videoIDRegister}, $methodDescriptor",
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
fun hookBackgroundPlayVideoID(
    methodDescriptor: String,
) = backgroundPlaybackMethod.addInstruction(
    backgroundPlaybackInsertIndex++, // move-result-object offset
    "invoke-static {v$backgroundPlaybackVideoIDRegister}, $methodDescriptor",
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
 * [hookVideoID] or [hookBackgroundPlayVideoID] instead.
 *
 * Be aware, this can be called multiple times for the same video ID.
 *
 * @param methodDescriptor which method to call. Params must be `Ljava/lang/String;Z`
 */
fun hookPlayerResponseVideoID(methodDescriptor: String) = addPlayerResponseMethodHook(
    Hook.VideoID(
        methodDescriptor,
    ),
)

private var videoIDRegister = 0
private var videoIDInsertIndex = 0
private lateinit var videoIDMethod: MutableMethod

private var backgroundPlaybackVideoIDRegister = 0
private var backgroundPlaybackInsertIndex = 0
private lateinit var backgroundPlaybackMethod: MutableMethod

val videoIDPatch = bytecodePatch(
    description = "Hooks to detect when the video ID changes.",
) {
    dependsOn(
        sharedExtensionPatch,
        playerResponseMethodHookPatch,
    )

    execute {
        VideoIDFingerprint.match(VideoIDParentFingerprint.originalClassDef).let {
            it.method.apply {
                videoIDMethod = this
                val index = it.instructionMatches[1].index
                videoIDRegister = getInstruction<OneRegisterInstruction>(index).registerA
                videoIDInsertIndex = index + 1
            }
        }

        VideoIDBackgroundPlayFingerprint.let {
            it.method.apply {
                backgroundPlaybackMethod = this
                val index = it.instructionMatches.first().index
                backgroundPlaybackVideoIDRegister = getInstruction<OneRegisterInstruction>(index + 1).registerA
                backgroundPlaybackInsertIndex = index + 2
            }
        }
    }
}