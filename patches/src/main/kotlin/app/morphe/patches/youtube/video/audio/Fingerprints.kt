/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3412
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.video.audio

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.Opcode

/**
 * Media3 DefaultAudioSink, where the audio session id of a newly created AudioTrack is read.
 */
internal object AudioTrackSessionIdFingerprint : Fingerprint(
    classFingerprint = Fingerprint(
        filters = listOf(
            string("ExoPlayer:AudioTrackReleaseThread")
        )
    ),
    filters = listOf(
        methodCall(smali = "Landroid/media/AudioTrack;->getAudioSessionId()I"),
        opcode(Opcode.MOVE_RESULT, location = MatchAfterImmediately())
    )
)
