/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.bottombar

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.newInstance

/**
 * Called when the bottom navigation bar state changes.
 */
internal object BottomBarStateChangeFingerprint : Fingerprint(
    definingClass = "Lcom/reddit/launch/bottomnav/BottomNavScreen;",
    returnType = "V",
    parameters = listOf("F"),
    filters = listOf(
        newInstance("Lcom/reddit/launch/bottomnav/BottomNavScreen\$onNavBarStateChange\$1;")
    )
)
