/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3109
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.misc.guest

import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.returnEarly
import app.morphe.patches.reddit.misc.version.is_2026_44_0_or_greater
import app.morphe.patches.reddit.misc.version.versionCheckPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT

@Suppress("unused")
val startAsGuestPatch = patch(
    name = "Start as guest",
    description = "Skips the forced startup login screen using Reddit's native guest browsing mode."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(versionCheckPatch)

    execute {
        // Force "onboarding complete"
        FrontPageApplicationHasFinishedOnboardingFingerprint.instructionMatches.last()
            .getMethodCalled().returnEarly(true)

        if (is_2026_44_0_or_greater) {
            // A server experiment can force logged out users to the login screen
            // once onboarding is complete.
            IsLoginForcedFingerprint.method.returnEarly(false)
        }
    }
}
