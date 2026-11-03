/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4278
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.misc.fix.guidecache

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.youtube.misc.playservice.is_21_28_or_greater
import app.morphe.patches.youtube.misc.playservice.is_21_31_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.util.insertLiteralOverride

internal val fixGuideCachePatch = bytecodePatch(
    description = "Fixes startup guide responses not being cached."
) {
    dependsOn(versionCheckPatch)

    execute {
        if (!is_21_28_or_greater || is_21_31_or_greater) return@execute

        // An empty guide cache can delay Subscriptions headers and navigation on startup (#4198).
        // In 21.28.208 and 21.30.216, 1073815114 can select the account cache while 1073830184
        // is off, leaving fresh responses without the account required for saving.
        // Backport the unconditional account copy from 21.43.523 and 21.44.528 at this check only.
        GuideResponseAccountFingerprint.let {
            it.method.insertLiteralOverride(
                it.instructionMatches.first().index,
                true
            )
        }
    }
}
