/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 */

package app.morphe.patches.youtube.layout.hide.shelves

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.youtube.misc.engagement.engagementPanelHookPatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.litho.filter.addLithoFilter
import app.morphe.patches.youtube.misc.litho.filter.lithoFilterPatch
import app.morphe.patches.youtube.misc.litho.observer.layoutReloadObserverPatch
import app.morphe.patches.youtube.misc.navigation.navigationBarHookPatch
import app.morphe.patches.youtube.misc.playertype.playerTypeHookPatch

private const val FILTER_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/youtube/patches/components/HorizontalShelvesFilter;"

internal val hideHorizontalShelvesPatch = bytecodePatch {
    dependsOn(
        sharedExtensionPatch,
        lithoFilterPatch,
        playerTypeHookPatch,
        navigationBarHookPatch,
        engagementPanelHookPatch,
        layoutReloadObserverPatch,
    )

    execute {
        addLithoFilter(FILTER_CLASS_DESCRIPTOR)
    }
}
