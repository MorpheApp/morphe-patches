/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */
package app.morphe.patches.reddit.layout.communities

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.misc.version.is_2026_16_0_or_greater
import app.morphe.patches.reddit.misc.version.is_2026_18_0_or_greater
import app.morphe.patches.reddit.misc.version.versionCheckPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.setExtensionIsPatchIncluded

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/reddit/patches/HideCommunitiesShelf;"

@Suppress("unused")
val hideCommunitiesShelf = bytecodePatch(
    name = "Hide communities shelf",
    description = "Adds an option to hide the related or suggested communities shelf in subreddits."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(settingsPatch, versionCheckPatch)

    execute {
        fun Fingerprint.applyHideShelf() {
            method.addInstructionsWithLabels(
                0,
                """
                    invoke-static { }, $EXTENSION_CLASS->hideCommunitiesShelf()Z
                    move-result v0
                    if-eqz v0, :off
                    return-void
                    :off
                    nop
                """
            )
        }

        val legacyFingerprint = if (is_2026_18_0_or_greater) CommunityRecommendationSection_2026_18_Fingerprint
        else if (is_2026_16_0_or_greater) CommunityRecommendationSection_2026_16_Fingerprint
        else CommunityRecommendationSectionLegacyFingerprint

        legacyFingerprint.applyHideShelf()
        CommunityRecommendationsComposeMethodFingerprint.applyHideShelf()

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}
