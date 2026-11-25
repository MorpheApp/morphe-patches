/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5331
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.misc.fix.litholayout

import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.shared.misc.fix.litholayout.sharedFixLithoLayoutLoopPatch

val fixLithoLayoutLoopPatch = sharedFixLithoLayoutLoopPatch(
    // The Litho configuration of YT Music is not checked, only the layout threads are changed.
    fixLayoutConfiguration = false,
    overrideMeasureItemsFlag = { false },
    forceRemoveReleasedItemHeightsFlag = { false },
    forceDetachOnlyOwnTreeFlag = { false }
) {
    dependsOn(sharedExtensionPatch)
}
