/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.shared

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import com.android.tools.smali.dexlib2.Opcode

/**
 * Main Link constructor, the only one that sets the url.
 */
internal object LinkConstructorFingerprint : Fingerprint(
    definingClass = "Lcom/reddit/domain/model/Link;",
    name = "<init>",
    returnType = "V",
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IPUT_OBJECT,
            definingClass = "Lcom/reddit/domain/model/Link;",
            name = "url",
            type = "Ljava/lang/String;"
        )
    )
)

/**
 * BottomNavScreen.onNavBarStateChange(float), called with the fraction of the navigation bar
 * that is shown.
 */
internal object NavBarStateChangeFingerprint : Fingerprint(
    definingClass = "Lcom/reddit/launch/bottomnav/BottomNavScreen;",
    returnType = "V",
    parameters = listOf("F"),
    filters = listOf(
        // The coroutine class is BottomNavScreen$onNavBarStateChange$1$1 in older app versions.
        methodCall(
            definingClass = "Lcom/reddit/launch/bottomnav/BottomNavScreen\$onNavBarStateChange\$1",
            name = "<init>"
        )
    )
)
