/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5303
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.communitymenu

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.Opcode

/**
 * Red dot badge on the community menu button.
 */
internal object CommunityMenuBadgeFingerprint : Fingerprint(
    name = "invoke",
    returnType = "Ljava/lang/Object;",
    filters = listOf(
        fieldAccess(
            opcode = Opcode.SGET_OBJECT,
            definingClass = "Lcom/reddit/ui/compose/ds/BadgeSentiment;",
            name = "Brand"
        ),
        string("community_menu_badge", location = MatchAfterWithin(5)),
        methodCall(
            opcode = Opcode.INVOKE_STATIC_RANGE,
            parameters = listOf(
                "Ljava/lang/String;",
                "L",
                "Lcom/reddit/ui/compose/ds/BadgeSentiment;",
                "Z",
                "Z",
                "L",
                "L",
                "I",
                "I"
            ),
            returnType = "V",
            location = MatchAfterWithin(12)
        ),
        opcode(Opcode.GOTO, location = MatchAfterImmediately())
    )
)
