/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5074
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.videosforyou

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.patch
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.misc.version.is_2026_40_0_or_greater
import app.morphe.patches.reddit.misc.version.versionCheckPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.setExtensionIsPatchIncluded
import java.util.logging.Logger

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/reddit/patches/HideVideosForYouShelfPatch;"

@Suppress("unused")
val hideVideosForYouShelfPatch = patch(
    name = "Hide Videos for you shelf",
    description = "Adds an option to hide the 'Videos for you' shelf in the home feed. " +
            "This patch works with Reddit 2026.40.0 and newer."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(settingsPatch, versionCheckPatch)

    execute {
        if (!is_2026_40_0_or_greater) {
            return@execute Logger.getLogger(this::class.java.name).warning(
                "'Hide Videos for you shelf' requires Reddit 2026.40.0+"
            )
        }

        VideoCarouselSectionComposeMethodFingerprint.method.addInstructionsWithLabels(
            0,
            """
                invoke-static { }, $EXTENSION_CLASS->hideVideosForYouShelf()Z
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
