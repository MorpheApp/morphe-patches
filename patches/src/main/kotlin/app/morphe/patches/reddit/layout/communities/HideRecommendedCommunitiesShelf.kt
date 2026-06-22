/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */
package app.morphe.patches.reddit.layout.communities

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.misc.version.is_2026_16_0_or_greater
import app.morphe.patches.reddit.misc.version.is_2026_18_0_or_greater
import app.morphe.patches.reddit.misc.version.versionCheckPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.setExtensionIsPatchIncluded

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/reddit/patches/HideRecommendedCommunitiesShelf;"

@Suppress("unused")
val hideRecommendedCommunitiesShelf = bytecodePatch(
    name = "Hide recommended communities shelf",
    description = "Adds an option to hide the recommended communities shelves in subreddits."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(settingsPatch, versionCheckPatch)

    execute {
        (if (is_2026_18_0_or_greater) CommunityRecommendationSection_2026_18_Fingerprint
        else if (is_2026_16_0_or_greater) CommunityRecommendationSection_2026_16_Fingerprint
        else CommunityRecommendationSectionLegacyFingerprint)
            .method.addInstructionsWithLabels(
            0,
            """
                invoke-static { }, $EXTENSION_CLASS->hideRecommendedCommunitiesShelf()Z
                move-result v0
                if-eqz v0, :off
                return-void
                :off
                nop
            """
        )

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}
