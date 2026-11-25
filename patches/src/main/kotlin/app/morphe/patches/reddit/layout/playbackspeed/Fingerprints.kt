/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5308
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.playbackspeed

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.checkCast
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import com.android.tools.smali.dexlib2.Opcode

internal object VideoPlaybackSpeedParserFingerprint : Fingerprint(
    returnType = "Ljava/lang/Object;",
    parameters = listOf("Ljava/lang/Object;"),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/reddit/features/VideoPlaybackSpeedVariant;",
            name = "getEntries"
        ),
        methodCall(
            definingClass = "Lcom/reddit/features/VideoPlaybackSpeedVariant;",
            name = "getVariant"
        ),
        methodCall(
            definingClass = "Lkotlin/jvm/internal/Intrinsics;",
            name = "areEqual"
        ),
        checkCast(
            "Lcom/reddit/features/VideoPlaybackSpeedVariant;",
            location = MatchAfterWithin(10)
        ),
        opcode(Opcode.RETURN_OBJECT, location = MatchAfterImmediately())
    )
)
