/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4891
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.interaction.sonos

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import com.android.tools.smali.dexlib2.Opcode

/**
 * Matches performClick on MdxMediaRouteButton.
 */
internal object MediaRouteButtonPerformClickFingerprint : Fingerprint(
    definingClass = "Lcom/google/android/libraries/youtube/mdx/mediaroute/MdxMediaRouteButton;",
    name = "performClick",
    returnType = "Z",
    parameters = emptyList()
)

/**
 * Matches ExoPlayer's AudioTrack.write(ByteBuffer, int, int) call.
 */
internal object AudioTrackWriteFingerprint : Fingerprint(
    returnType = "I",
    filters = listOf(
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            definingClass = "Landroid/media/AudioTrack;",
            name = "write",
            parameters = listOf("Ljava/nio/ByteBuffer;", "I", "I")
        )
    )
)

/**
 * Matches ExoPlayer's DefaultAudioSink phone speaker volume muting method.
 */
internal object AudioSinkMuteFingerprint : Fingerprint(
    classFingerprint = AudioTrackWriteFingerprint,
    returnType = "V",
    parameters = emptyList(),
    filters = listOf(
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            definingClass = "Landroid/media/AudioTrack;",
            name = "setVolume",
            parameters = listOf("F")
        )
    )
)
