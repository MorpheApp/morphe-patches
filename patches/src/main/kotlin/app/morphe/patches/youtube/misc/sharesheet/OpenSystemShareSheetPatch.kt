/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.patches.youtube.misc.sharesheet

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.litho.filter.addLithoFilter
import app.morphe.patches.youtube.misc.litho.filter.lithoFilterPatch
import app.morphe.patches.youtube.misc.recyclerviewtree.hook.addRecyclerViewTreeHook
import app.morphe.patches.youtube.misc.recyclerviewtree.hook.recyclerViewTreeHookPatch
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/OpenSystemShareSheetPatch;"
private const val EXTENSION_FILTER =
    "Lapp/morphe/extension/youtube/patches/components/SystemShareSheetFilter;"

@Suppress("unused")
internal fun openSystemShareSheetPatch(
) = bytecodePatch(
    name = "Open system share sheet",
    description = "Adds an option to always open the system share sheet instead of the in-app share sheet."
) {

    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        lithoFilterPatch,
        recyclerViewTreeHookPatch
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.GENERAL.addPreferences(
            SwitchPreference("morphe_open_system_share_sheet", summary = true)
        )

        addRecyclerViewTreeHook(EXTENSION_CLASS)

        addLithoFilter(EXTENSION_FILTER)
    }
}