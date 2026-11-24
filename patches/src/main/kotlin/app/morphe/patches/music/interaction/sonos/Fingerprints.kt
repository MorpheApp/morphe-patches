/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4891
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.interaction.sonos

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.Opcode

internal object MdxMediaRouteButtonPerformClickFingerprint : Fingerprint(
    definingClass = "Lcom/google/android/libraries/youtube/mdx/mediaroute/MdxMediaRouteButton;",
    name = "performClick",
    returnType = "Z",
    parameters = listOf()
)

private object MediaRouteButtonControllerFingerprint : Fingerprint(
    name = "<clinit>",
    filters = listOf(
        string("MediaRouteButtonController", StringComparisonType.ENDS_WITH)
    )
)

/**
 * Updates if Cast devices are available, which shows or hides all Cast buttons.
 */
internal object MediaRouteButtonControllerUpdateFingerprint : Fingerprint(
    classFingerprint = MediaRouteButtonControllerFingerprint,
    returnType = "V",
    parameters = listOf(),
    filters = listOf(
        // MediaRouter.isRouteAvailable(selector, flags)
        methodCall(
            opcode = Opcode.INVOKE_STATIC,
            parameters = listOf("L", "I"),
            returnType = "Z"
        ),
        opcode(Opcode.MOVE_RESULT, location = MatchAfterImmediately()),
        // The previous value.
        fieldAccess(
            definingClass = "this",
            type = "Z",
            opcode = Opcode.IGET_BOOLEAN,
            location = MatchAfterImmediately()
        )
    )
)

/**
 * The method of the ExoPlayer audio sink that writes the decoded audio to the AudioTrack.
 * It is the only caller of this AudioTrack.write overload in the app.
 */
internal object AudioSinkWriteFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(),
    filters = listOf(
        methodCall(
            definingClass = "Landroid/media/AudioTrack;",
            name = "write",
            parameters = listOf("Ljava/nio/ByteBuffer;", "I", "I"),
            returnType = "I"
        )
    )
)

/**
 * The method of the same audio sink that applies the player volume to the AudioTrack.
 */
internal object AudioSinkSetVolumeFingerprint : Fingerprint(
    classFingerprint = AudioSinkWriteFingerprint,
    returnType = "V",
    parameters = listOf(),
    filters = listOf(
        methodCall(
            definingClass = "Landroid/media/AudioTrack;",
            name = "setVolume",
            parameters = listOf("F"),
            returnType = "I"
        )
    )
)
