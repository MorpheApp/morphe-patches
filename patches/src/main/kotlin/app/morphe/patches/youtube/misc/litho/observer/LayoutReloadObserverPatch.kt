/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

@file:Suppress("SpellCheckingInspection")

package app.morphe.patches.youtube.misc.litho.observer

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.litho.lazily.hookTreeNodeResult
import app.morphe.patches.youtube.misc.litho.lazily.lazilyConvertedElementHookPatch

internal const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/youtube/patches/LayoutReloadObserverPatch;"

val layoutReloadObserverPatch = bytecodePatch(
    description = "Hooks a method to detect in the extension when the RecyclerView at the bottom of the player is redrawn.",
) {
    dependsOn(
        sharedExtensionPatch,
        lazilyConvertedElementHookPatch
    )

    execute {
        hookTreeNodeResult("$EXTENSION_CLASS_DESCRIPTOR->onLazilyConvertedElementLoaded")
    }
}