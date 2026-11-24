/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5245
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.backtoexit

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.methodCall
import app.morphe.patcher.newInstance
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.Opcode

/**
 * Back press handler of the home screen.
 *
 * If the current home feed reports that back should refresh it (it is scrolled down), the feed is
 * scrolled to the top and reloaded. Otherwise the first back shows the exit hint toast (only the
 * first few times ever) and a second back within a short time exits the app.
 */
internal object HomeBackPressedFingerprint : Fingerprint(
    name = "invoke",
    returnType = "Ljava/lang/Object;",
    parameters = listOf(),
    filters = listOf(
        // Current home feed screen.
        methodCall(
            definingClass = "Lcom/reddit/feedslegacy/switcher/impl/homepager/compose/HomePagerScreen;",
            returnType = "Lcom/reddit/screen/BaseScreen;",
            parameters = listOf()
        ),
        // Feed screen "back should refresh" check.
        methodCall(
            returnType = "Z",
            parameters = listOf(),
            location = MatchAfterWithin(20)
        ),
        opcode(Opcode.MOVE_RESULT, location = MatchAfterImmediately()),
        // Exit hint toast was shown less than the maximum number of times.
        newInstance($$"Lcom/reddit/feedslegacy/switcher/impl/exitapp/ExitAppToastSharedPreferences$toastSeenTotalCount$1;"),
        opcode(Opcode.IF_EQZ, location = MatchAfterWithin(5)),
        string("onBackPressedHandler"),
        newInstance($$"Lcom/reddit/feedslegacy/switcher/impl/exitapp/RedditExitAppOnDoubleBackClickDelegate$handleAppExit$1;")
    )
)
