package app.morphe.patches.reddit.layout.trendingtoday

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.setExtensionIsPatchIncluded

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/reddit/patches/HideTrendingTodayShelfPatch;"

@Suppress("unused")
val hideTrendingTodayShelfPatch = bytecodePatch(
    name = "Hide Trending Today shelf",
    description = "Adds an option to hide the Trending Today shelf from search suggestions."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(settingsPatch)

    execute {

        // region patch for hide trending today title.

        SearchTypeaheadListDefaultPresentationConstructorFingerprint.match(
            SearchTypeaheadListDefaultPresentationToStringFingerprint.classDef
        ).method.addInstructions(
            1,
            """
                invoke-static { p1 }, $EXTENSION_CLASS_DESCRIPTOR->removeTrendingLabel(Ljava/lang/String;)Ljava/lang/String;
                move-result-object p1
            """
        )

        // endregion

        // region patch for hide trending today contents.

        listOf(
            TrendingTodayItemFingerprint,
            TrendingTodayItemLegacyFingerprint
        ).forEach { fingerprint ->
            fingerprint.method.addInstructionsWithLabels(
                0,
                """
                    invoke-static { }, $EXTENSION_CLASS_DESCRIPTOR->hideTrendingTodayShelf()Z
                    move-result v0
                    if-eqz v0, :ignore
                    return-void
                    :ignore
                    nop
                """
            )
        }

        // endregion

        setExtensionIsPatchIncluded(EXTENSION_CLASS_DESCRIPTOR)
    }
}
