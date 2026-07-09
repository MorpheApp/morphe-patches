/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

@file:Suppress("SpellCheckingInspection")

package app.morphe.patches.youtube.misc.litho.filter

import app.morphe.patches.shared.misc.litho.filter.sharedLithoFilterPatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.fix.backtoexitgesture.fixBackToExitGesturePatch
import app.morphe.patches.youtube.misc.fix.verticalscroll.fixVerticalScrollPatch
import app.morphe.patches.youtube.misc.litho.context.conversionContextPatch
import app.morphe.patches.youtube.misc.playservice.is_20_22_or_greater
import app.morphe.patches.youtube.misc.playservice.is_21_15_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch

val lithoFilterPatch = sharedLithoFilterPatch(
    // YouTube 20.22+ always uses the native Upb encode path.
    hookNonNativeBuffer = { !is_20_22_or_greater },
    // Flag was removed in 21.15+.
    overrideUpbFeatureFlag = { !is_21_15_or_greater }
) {
    dependsOn(
        sharedExtensionPatch,
        conversionContextPatch,
        versionCheckPatch,
        fixBackToExitGesturePatch,
        fixVerticalScrollPatch
    )
}
