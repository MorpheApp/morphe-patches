/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

@file:Suppress("SpellCheckingInspection")

package app.morphe.patches.youtube.video.voiceovertranslation

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.anyInstruction
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.newInstance
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

private object AudioSinkSetSpeedMethodFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(),
    filters = listOf(
        methodCall("Landroid/media/PlaybackParams;->setSpeed(F)Landroid/media/PlaybackParams;"),
        anyInstruction(
            string("AudioTrackAudioOutput"),
            string("DefaultAudioSink") // 20.21.37
        ),
        string("Failed to set playback params")
    )
)

internal object AudioSinkSetVolumeFingerprint : Fingerprint(
    classFingerprint = AudioSinkSetSpeedMethodFingerprint,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("F"),
    filters = listOf(
        fieldAccess(
            definingClass = "this",
            opcode = Opcode.IGET,
            type = "F"
        ),
        opcode(
            opcode = Opcode.CMPL_FLOAT,
            location = MatchAfterWithin(10)
        ),
        fieldAccess(
            definingClass = "this",
            opcode = Opcode.IPUT,
            type = "F",
            location = MatchAfterWithin(10)
        ),
        opcode(
            opcode = Opcode.RETURN_VOID,
            location = MatchAfterWithin(10)
        )
    )
)

internal object AudioTrackWrapperInitFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    returnType = "V",
    parameters = listOf("Landroid/media/AudioTrack;"),
    filters = listOf(
        newInstance("Landroid/media/AudioTimestamp;"),
        fieldAccess(
            opcode = Opcode.IPUT_OBJECT,
            definingClass = "this",
            type = "Landroid/media/AudioTimestamp;",
            location = MatchAfterWithin(5)
        )
    )
)
