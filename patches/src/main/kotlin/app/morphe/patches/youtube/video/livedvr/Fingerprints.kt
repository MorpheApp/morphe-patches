/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.patches.youtube.video.livedvr

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.literal
import app.morphe.patcher.opcode
import app.morphe.patches.youtube.video.quality.VideoStreamingDataToStringFingerprint
import com.android.tools.smali.dexlib2.Opcode

// Returns false only for playbackType 8 (non-DVR live).
// Forcing true enables seeking on live streams.
// Note: another no-param boolean method in the same class also checks literal(8),
// but adds a type 9 check, placing CONST_4 at +4 there, vs +2 here.
internal object VideoStreamingDataAllowSeekingFingerprint : Fingerprint(
    classFingerprint = VideoStreamingDataToStringFingerprint,
    returnType = "Z",
    parameters = listOf(),
    filters = listOf(
        literal(8),
        opcode(Opcode.IF_EQ, location = MatchAfterImmediately()),
        literal(1, location = MatchAfterImmediately()),
    )
)
