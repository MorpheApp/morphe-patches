/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

@file:Suppress("SpellCheckingInspection")

package app.morphe.patches.music.misc.litho.filter

import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.shared.misc.litho.context.conversionContextPatch
import app.morphe.patches.shared.misc.litho.filter.sharedLithoFilterPatch

val lithoFilterPatch = sharedLithoFilterPatch(
    // YT Music does not ship the Upb feature flag.
    overrideUpbFeatureFlag = { false },
    removeFilteredListItems = false
) {
    dependsOn(
        sharedExtensionPatch,
        conversionContextPatch
    )
}
