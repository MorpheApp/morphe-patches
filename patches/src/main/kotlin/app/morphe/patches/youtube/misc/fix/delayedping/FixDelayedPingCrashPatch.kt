/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.misc.fix.delayedping

import app.morphe.patcher.extensions.InstructionExtensions.removeInstruction
import app.morphe.patcher.patch.patch
import app.morphe.patches.youtube.misc.playservice.is_21_28_or_greater
import app.morphe.patches.youtube.misc.playservice.is_21_32_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch

internal val fixDelayedPingCrashPatch = patch(
    description = "Fixes a crash when a delayed ping is sent, such as when opening a Short."
) {
    dependsOn(versionCheckPatch)

    execute {
        if (!is_21_28_or_greater || is_21_32_or_greater) return@execute

        // A delayed ping always gives its listener a null result, but 21.28 - 21.31 check that
        // the result is not null, so the app crashes when the ping is sent (#5032).
        // Remove the check, as 21.32 and newer do. The listener then passes the result on as 21.32 does.
        DelayedPingResponseListenerFingerprint.let {
            it.method.removeInstruction(it.instructionMatches[1].index)
        }
    }
}
