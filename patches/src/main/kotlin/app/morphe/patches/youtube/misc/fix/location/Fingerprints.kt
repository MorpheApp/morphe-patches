/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5080
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.misc.fix.location

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import com.android.tools.smali.dexlib2.Opcode

/**
 * Country of the requests, which is the stored country if the server enables it,
 * and otherwise the Location setting, the country of the SIM card or the country of the language.
 * 21.16 and newer.
 */
internal object RequestCountryFingerprint : Fingerprint(
    returnType = "Ljava/lang/String;",
    filters = listOf(
        // If the stored country is used.
        literal(0x40017fdb),
        // Stored country.
        literal(0x107fcb, location = MatchAfterWithin(5)),
        methodCall(
            parameters = listOf("I"),
            returnType = "I",
            location = MatchAfterImmediately()
        ),
        opcode(Opcode.MOVE_RESULT, location = MatchAfterImmediately())
    )
)
