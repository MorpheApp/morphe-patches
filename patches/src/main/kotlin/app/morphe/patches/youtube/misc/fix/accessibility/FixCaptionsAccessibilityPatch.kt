/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4136
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.misc.fix.accessibility

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.youtube.misc.playservice.is_21_23_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch

internal val fixCaptionsAccessibilityPatch = bytecodePatch(
    description = "Fixes captions not being announced by screen readers."
) {
    dependsOn(versionCheckPatch)

    execute {
        if (is_21_23_or_greater) return@execute

        // YouTube 21.23.492 uses ACCESSIBILITY_LIVE_REGION_POLITE to announce caption updates.
        // Backport it so TalkBack can read changing captions without accessibility focus (#3095).
        CaptionTextViewConstructorFingerprint.let {
            it.method.addInstructions(
                it.instructionMatches.last().index,
                """
                    const/4 v0, 0x1
                    invoke-virtual { p0, v0 }, Landroid/view/View;->setAccessibilityLiveRegion(I)V
                """
            )
        }
    }
}
