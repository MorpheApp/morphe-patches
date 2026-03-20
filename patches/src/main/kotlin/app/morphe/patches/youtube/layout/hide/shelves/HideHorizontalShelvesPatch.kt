/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
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
