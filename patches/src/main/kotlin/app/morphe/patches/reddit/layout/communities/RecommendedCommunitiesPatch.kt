package app.morphe.patches.reddit.layout.communities

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.setExtensionIsPatchIncluded

private const val EXTENSION_CLASS_DESCRIPTOR = "Lapp/morphe/extension/reddit/patches/RecommendedCommunitiesPatch;"

@Suppress("unused")
val recommendedCommunitiesPatch = bytecodePatch(
    name = "Hide recommended communities shelf",
    description = "Adds an option to hide the recommended communities shelves in subreddits."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(settingsPatch)

    execute {
        communityRecommendationSectionFingerprint.match(
            communityRecommendationSectionParentFingerprint.classDef
        ).method.apply {
            addInstructionsWithLabels(
                0,
                """
                    invoke-static {}, $EXTENSION_CLASS_DESCRIPTOR->hideRecommendedCommunitiesShelf()Z
                    move-result v0
                    if-eqz v0, :off
                    return-void
                """, ExternalLabel("off", getInstruction(0))
            )
        }

        setExtensionIsPatchIncluded(EXTENSION_CLASS_DESCRIPTOR)
    }
}
