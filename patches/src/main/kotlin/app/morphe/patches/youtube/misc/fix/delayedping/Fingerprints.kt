/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.misc.fix.delayedping

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.checkCast
import app.morphe.patcher.methodCall
import app.morphe.patcher.string

/**
 * Listener of a response, which a delayed ping gets with a null result.
 * 21.28 - 21.31 check that the result is not null.
 */
internal object DelayedPingResponseListenerFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Ljava/lang/Object;"),
    filters = listOf(
        checkCast("Ljava/lang/Void;"),
        methodCall(
            definingClass = "Ljava/lang/Object;",
            name = "getClass",
            location = MatchAfterImmediately()
        ),
        string("crash_report_id")
    )
)
