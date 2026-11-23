/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.commentlines

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.newInstance
import app.morphe.patcher.opcode
import com.android.tools.smali.dexlib2.Opcode

/**
 * Draws the indent lines for nested comments.
 */
internal object CommentIndentLinesFingerprint : Fingerprint(
    name = "invoke",
    returnType = "Ljava/lang/Object;",
    parameters = listOf("Ljava/lang/Object;"),
    filters = listOf(
        literal(0.4f),
        newInstance("Lkotlin/ranges/IntRange;"),
        methodCall(name = "getLayoutDirection"),
        methodCall(
            returnType = "V",
            parameters = listOf("L", "J", "J", "J", "F", "I", "L", "F", "I")
        ),
        opcode(Opcode.IF_EQ, location = MatchAfterImmediately())
    )
)
