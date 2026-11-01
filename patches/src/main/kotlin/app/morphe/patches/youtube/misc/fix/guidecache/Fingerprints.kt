/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4278
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.misc.fix.guidecache

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal object GuideResponseAccountFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Lcom/google/common/util/concurrent/ListenableFuture;",
    parameters = listOf("L"),
    filters = listOf(
        literal(1073830184L),
        methodCall(
            opcode = Opcode.INVOKE_INTERFACE,
            parameters = listOf("I"),
            returnType = "Z",
            location = MatchAfterWithin(5)
        ),
        opcode(Opcode.MOVE_RESULT, location = MatchAfterImmediately())
    )
)
