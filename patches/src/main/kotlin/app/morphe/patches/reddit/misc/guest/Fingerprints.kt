/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3109
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.misc.guest

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.methodCall
import app.morphe.patcher.newInstance
import com.android.tools.smali.dexlib2.Opcode

internal object FrontPageApplicationHasFinishedOnboardingFingerprint : Fingerprint(
    definingClass = "Lcom/reddit/frontpage/FrontpageApplication;",
    filters = listOf(
        methodCall(smali = "Lcom/reddit/session/Session;->isLoggedIn()Z"),
        methodCall(smali = "Lcom/reddit/session/Session;->isIncognito()Z"),
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            returnType = "Z",
            parameters = listOf(),
            location = MatchAfterWithin(10)
        )
    )
)

/**
 * RedditForceLoginGate.isLoginForced(). Reddit 2026.44.0+
 */
internal object IsLoginForcedFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf(),
    filters = listOf(
        methodCall(smali = "Lcom/reddit/session/Session;->isLoggedOut()Z"),
        newInstance($$"Lcom/reddit/auth/login/forcelogin/RedditForceLoginGate$isLoginForced$")
    )
)
