/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */
package app.morphe.patches.reddit.layout.trendingtoday

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.misc.version.is_2026_11_0_or_greater
import app.morphe.patches.reddit.misc.version.versionCheckPatch
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

    dependsOn(settingsPatch, versionCheckPatch)

    execute {

        // region patch for hide trending today title.

        SearchTypeaheadListDefaultPresentationConstructorFingerprint.method.addInstructions(
            1,
            """
                invoke-static { p1 }, $EXTENSION_CLASS_DESCRIPTOR->removeTrendingLabel(Ljava/lang/String;)Ljava/lang/String;
                move-result-object p1
            """
        )

        // endregion

        // region patch for hide trending today contents.

        fun Fingerprint.applyHideTrendingToday() {
            method.addInstructionsWithLabels(
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

        TrendingTodayItemFingerprint.applyHideTrendingToday()

        if (!is_2026_11_0_or_greater) {
            // Legacy seems to be removed in 2026.11.0+
            TrendingTodayItemLegacyFingerprint.applyHideTrendingToday()
        }

        // endregion

        setExtensionIsPatchIncluded(EXTENSION_CLASS_DESCRIPTOR)
    }
}
