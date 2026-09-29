/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3387
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.feedrefresh

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.Opcode

/**
 * Stores when the loaded feed expires (the first field) and when the feed refresh is scheduled
 * (the second field), both in milliseconds.
 */
internal object FeedExpirationFingerprint : Fingerprint(
    returnType = "V",
    filters = listOf(
        string("FEmemberships_and_purchases"),
        string("FEmembership_detail"),
        opcode(Opcode.ADD_LONG),
        fieldAccess(opcode = Opcode.IPUT_WIDE, location = InstructionLocation.MatchAfterImmediately()),
        opcode(Opcode.ADD_LONG_2ADDR, InstructionLocation.MatchAfterWithin(10)),
        fieldAccess(opcode = Opcode.IPUT_WIDE, location = InstructionLocation.MatchAfterImmediately())
    )
)
