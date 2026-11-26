/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5241
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.commentlines

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import com.android.tools.smali.dexlib2.Opcode

/**
 * Draw behind lambda of a comment that draws one indent line for each reply level.
 */
internal object CommentIndentLinesFingerprint : Fingerprint(
    name = "invoke",
    returnType = "Ljava/lang/Object;",
    parameters = listOf("Ljava/lang/Object;"),
    filters = listOf(
        // Alpha of faded lines.
        literal(0.4f),
        // Alpha of each reply level.
        methodCall(definingClass = "Ljava/util/ArrayList;", name = "get"),
        methodCall(name = "getLayoutDirection"),
        // drawLine(color, start, end, strokeWidth, cap, pathEffect, alpha, default arguments mask)
        methodCall(
            returnType = "V",
            parameters = listOf("L", "J", "J", "J", "F", "I", "L", "F", "I")
        ),
        // Loop check of the reply level.
        opcode(Opcode.IF_EQ, location = MatchAfterWithin(5))
    )
)
