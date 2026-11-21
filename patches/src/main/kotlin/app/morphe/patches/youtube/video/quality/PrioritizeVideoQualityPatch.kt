/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.video.quality

import app.morphe.patches.shared.misc.videoquality.prioritizeVideoQualityPatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.video.format.videoFormatPatch

internal val prioritizeVideoQualityPatch = prioritizeVideoQualityPatch(
    block = {
        dependsOn(
            sharedExtensionPatch,
            settingsPatch,
            videoFormatPatch,
        )
    },
    addPreference = { settingsMenuVideoQualityGroup.add(it) }
)
