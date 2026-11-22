/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.shared.misc.videoquality

import app.morphe.patcher.patch.PatchDefinition
import app.morphe.patcher.patch.patch
import app.morphe.patches.shared.misc.settings.preference.BasePreference
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.shared.misc.videoformat.hookAdaptiveFormat

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/shared/patches/PrioritizeVideoQualityPatch;"

/**
 * @param addPreference Adds the preference of the patch to the video quality settings of the app.
 */
internal fun prioritizeVideoQualityPatch(
    block: PatchDefinition.() -> Unit,
    addPreference: (BasePreference) -> Unit
) = patch {
    block()

    execute {
        addPreference(
            SwitchPreference("morphe_prioritize_video_quality", summary = true)
        )

        hookAdaptiveFormat("$EXTENSION_CLASS->prioritizeVideoQuality")
    }
}
