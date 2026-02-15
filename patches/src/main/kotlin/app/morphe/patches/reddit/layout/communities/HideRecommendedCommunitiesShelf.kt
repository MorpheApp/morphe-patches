package app.morphe.patches.reddit.layout.communities

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.setExtensionIsPatchIncluded

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/reddit/patches/HideRecommendedCommunitiesShelf;"

@Suppress("unused")
val hideRecommendedCommunitiesShelf = bytecodePatch(
    name = "Hide recommended communities shelf",
    description = "Adds an option to hide the recommended communities shelves in subreddits."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(settingsPatch)

    execute {
        CommunityRecommendationSectionFingerprint.match(
            CommunityRecommendationSectionParentFingerprint.originalClassDef
        ).method.addInstructionsWithLabels(
            0,
            """
                invoke-static { }, $EXTENSION_CLASS_DESCRIPTOR->hideRecommendedCommunitiesShelf()Z
                move-result v0
                if-eqz v0, :off
                return-void
                :off
                nop
            """
        )

        setExtensionIsPatchIncluded(EXTENSION_CLASS_DESCRIPTOR)
    }
}
