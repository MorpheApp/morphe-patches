/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.misc.backtoexit

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.methodCall
import app.morphe.patcher.newInstance
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.Opcode

/**
 * Back press handler on the home feed.
 */
internal object BackToExitFingerprint : Fingerprint(
    name = "invoke",
    returnType = "Ljava/lang/Object;",
    parameters = listOf(),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/reddit/feedslegacy/switcher/impl/homepager/compose/HomePagerScreen;",
            returnType = "Lcom/reddit/screen/BaseScreen;",
        ),
        methodCall(
            returnType = "Z",
            parameters = listOf(),
            location = MatchAfterWithin(15),
        ),
        opcode(Opcode.MOVE_RESULT, location = MatchAfterImmediately()),
        methodCall(
            definingClass = "Landroid/os/SystemClock;",
            name = "uptimeMillis",
        ),
        methodCall(
            returnType = "Z",
            parameters = listOf(),
        ),
        opcode(Opcode.MOVE_RESULT, location = MatchAfterImmediately()),
        string("onBackPressedHandler"),
        newInstance("Lcom/reddit/feedslegacy/switcher/impl/exitapp/RedditExitAppOnDoubleBackClickDelegate\$handleAppExit\$1;"),
    ),
)
