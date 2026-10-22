/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.misc.fix.playbackspeed

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterLast
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.literal
import app.morphe.patcher.opcode
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

/**
 * This method is usually used to set the initial speed (1.0x) when playback starts from the feed.
 * For some reason, in the latest YouTube, it is invoked even after the video has already started.
 */
internal object PlaybackSpeedInFeedsFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("L"),
    filters = listOf(
        opcode(Opcode.IGET),
        literal(1000, opcodes = listOf(Opcode.MUL_INT_LIT16), MatchAfterImmediately()),
        fieldAccess(
            opcode = Opcode.IGET_WIDE,
            definingClass = "this",
            type = "J",
            location = MatchAfterImmediately()
        ),
        literal(-1, opcodes = listOf(Opcode.CONST_WIDE_16), MatchAfterImmediately()),
        opcode(Opcode.CMP_LONG, MatchAfterImmediately()),
        opcode(Opcode.IF_EQZ, MatchAfterImmediately()),
        opcode(Opcode.IF_LEZ, MatchAfterImmediately()),
        opcode(Opcode.SUB_LONG_2ADDR, MatchAfterImmediately()),
        fieldAccess(
            opcode = Opcode.IGET,
            type = "F",
            location = MatchAfterLast()
        )
    )
)
