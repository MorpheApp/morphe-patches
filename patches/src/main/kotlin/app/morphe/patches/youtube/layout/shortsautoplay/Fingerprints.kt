package app.morphe.patches.youtube.layout.shortsautoplay

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal object ReelEnumConstructorFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.STATIC, AccessFlags.CONSTRUCTOR),
    filters = listOf(
        string("REEL_LOOP_BEHAVIOR_UNKNOWN"),
        string("REEL_LOOP_BEHAVIOR_SINGLE_PLAY"),
        string("REEL_LOOP_BEHAVIOR_REPEAT"),
        string("REEL_LOOP_BEHAVIOR_END_SCREEN"),
        opcode(Opcode.RETURN_VOID)
    )
)

internal object ReelPlaybackRepeatParentFingerprint : Fingerprint(
    returnType = "V",
    filters = listOf(
        string("Reels[%s] Playback Time: %d ms")
    )
)

/**
 * Matches class found in [reelPlaybackRepeatParentFingerprint].
 */
internal object ReelPlaybackRepeatFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("L"),
    filters = listOf(
        methodCall(smali = "Lcom/google/common/util/concurrent/ListenableFuture;->isDone()Z")
    )
)

internal object ReelPlaybackFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("J"),
    filters = listOf(
        fieldAccess(
            definingClass = "Ljava/util/concurrent/TimeUnit;",
            name = "MILLISECONDS"
        ),
        methodCall(
            name = "<init>",
            parameters = listOf("I", "L", "L"),
            location = InstructionLocation.MatchAfterWithin(15)
        ),
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            parameters = listOf("L"),
            returnType = "I",
            location = InstructionLocation.MatchAfterWithin(5)
        )
    )
)