/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3386
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.video.buffer

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.literal
import app.morphe.patcher.opcode
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

/**
 * The player load control's shouldContinueLoading.
 */
internal object ShouldContinueLoadingFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Z",
    parameters = listOf("L"),
    filters = listOf(
        opcode(Opcode.IGET_WIDE),
        literal(500000L),
        literal(120000),
        literal(15000L)
    )
)

/**
 * The player load control's onTracksSelected, where the memory limit is calculated.
 */
internal object TracksSelectedFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("L", "[L"),
    filters = listOf(
        literal(389),
        literal(38),
        literal(1024, listOf(Opcode.MUL_INT_LIT16)),
    )
)
