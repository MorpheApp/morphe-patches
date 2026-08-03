/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.misc.fix.zoom

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal object FullscreenGestureZoomFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf(),
    filters = listOf(
        opcode(opcode = Opcode.IF_EQZ),
        opcode(opcode = Opcode.MOVE, location = MatchAfterImmediately()),
        opcode(opcode = Opcode.GOTO, location = MatchAfterImmediately()),
        opcode(opcode = Opcode.MOVE, location = MatchAfterImmediately()),
        opcode(opcode = Opcode.IPUT_BOOLEAN, location = MatchAfterImmediately()),
        opcode(opcode = Opcode.IGET_OBJECT, location = MatchAfterImmediately()),
        opcode(opcode = Opcode.IGET_OBJECT, location = MatchAfterImmediately()),
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            parameters = listOf("J", "Z"),
            returnType = "Z",
            location = MatchAfterImmediately()
        ),
        opcode(opcode = Opcode.MOVE_RESULT, location = MatchAfterImmediately()),
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            parameters = listOf("J", "Z"),
            returnType = "Z",
            location = MatchAfterWithin(12)
        )
    )
)
