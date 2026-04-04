package app.morphe.patches.youtube.video.livedvr

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.literal
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

// Copied from app.morphe.patches.youtube.video.quality.
private object VideoStreamingDataToStringFingerprint : Fingerprint(
    name = "toString",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Ljava/lang/String;",
    filters = listOf(
        string("VideoStreamingData(itags=")
    )
)

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
        opcode(Opcode.CONST_4, location = MatchAfterWithin(2)),
    )
)
