/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5331
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.misc.fix.litholayout

import app.morphe.patches.shared.misc.fix.litholayout.sharedFixLithoLayoutLoopPatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playservice.is_20_51_or_greater
import app.morphe.patches.youtube.misc.playservice.is_21_23_or_greater
import app.morphe.patches.youtube.misc.playservice.is_21_28_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch

val fixLithoLayoutLoopPatch = sharedFixLithoLayoutLoopPatch(
    fixLayoutConfiguration = true,
    // The flag was added in 21.28.
    overrideMeasureItemsFlag = { is_21_28_or_greater },
    // The flag was added in 21.23.
    forceRemoveReleasedItemHeightsFlag = { is_21_23_or_greater },
    // The flag was added in 20.51.
    forceDetachOnlyOwnTreeFlag = { is_20_51_or_greater }
) {
    dependsOn(
        sharedExtensionPatch,
        versionCheckPatch
    )
}
