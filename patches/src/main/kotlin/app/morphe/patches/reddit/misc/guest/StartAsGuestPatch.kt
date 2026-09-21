/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3109
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.misc.guest

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.returnEarly

@Suppress("unused")
val startAsGuestPatch = bytecodePatch(
    name = "Start as guest",
    description = "Skips the forced startup login screen using Reddit's native guest browsing mode."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    execute {
        // Force "onboarding complete"
        FrontPageApplicationHasFinishedOnboardingFingerprint.instructionMatches.last()
            .getMethodCalled().returnEarly(true)
    }
}
