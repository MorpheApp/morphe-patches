/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.misc.fix.padding

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.matchAllMethodIndicesForEach
import app.morphe.patches.youtube.misc.playservice.is_21_07_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.util.insertLiteralOverride

internal val fixPagePaddingPatch = bytecodePatch(
    description = "Fixes layout padding glitches in incognito search and watch history."
) {
    dependsOn(versionCheckPatch)
    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        if (is_21_07_or_greater) {
            IncognitoSearchPaddingFeatureFlagFingerprint.matchAllMethodIndicesForEach(requireMatches = false) { index ->
                insertLiteralOverride(index, false)
            }
        }

        FeedBehindNavigationBarFeatureFlagsFingerprint.matchAllMethodIndicesForEach(requireMatches = false) { index ->
            insertLiteralOverride(index, false)
        }
    }
}
