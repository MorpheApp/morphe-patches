/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.layout.shortsautoplay

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.anyInstruction
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

private object ReelPlaybackRepeatParentFingerprint : Fingerprint(
    returnType = "V",
    filters = listOf(
        methodCall($$"Lj$/time/Instant;->toEpochMilli()J"),
        string("r_tr")
    )
)

/**
 * @param reelEnumClass The loop behavior enum. 21.43+ has a sibling method that
 *                      also uses Optional, but does not look up the loop behavior.
 */
internal fun reelPlaybackRepeatFingerprint(reelEnumClass: String) = Fingerprint(
    classFingerprint = ReelPlaybackRepeatParentFingerprint,
    returnType = "V",
    parameters = listOf("L"),
    filters = listOf(
        anyInstruction(
            methodCall(smali = "Lcom/google/common/util/concurrent/ListenableFuture;->isDone()Z"),
            methodCall(smali = $$"Lj$/util/Optional;->ofNullable(Ljava/lang/Object;)Lj$/util/Optional;") // 21.17+
        ),
        methodCall(returnType = reelEnumClass)
    )
)

/**
 * 21.42+
 */
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
            parameters = listOf("I", "L", "L", "Z"),
            location = MatchAfterWithin(15)
        ),
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            parameters = listOf("L"),
            returnType = "I",
            location = MatchAfterWithin(5)
        )
    )
)

internal object ReelPlaybackLegacyFingerprint : Fingerprint(
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
            location = MatchAfterWithin(15)
        ),
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            parameters = listOf("L"),
            returnType = "I",
            location = MatchAfterWithin(5)
        )
    )
)

/**
 * Finds a read of a field of the method's own class by field type.
 * Match it against a specific method.
 */
internal fun getInstanceFieldGetFingerprint(fieldType: String) = object : Fingerprint(
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            definingClass = "this",
            type = fieldType
        )
    )
) {}
