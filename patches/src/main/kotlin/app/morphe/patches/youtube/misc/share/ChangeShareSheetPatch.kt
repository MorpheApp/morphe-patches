/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 */

package app.morphe.patches.youtube.misc.share

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.litho.filter.addLithoFilter
import app.morphe.patches.youtube.misc.litho.filter.lithoFilterPatch
import app.morphe.patches.youtube.misc.recyclerviewtree.hook.addRecyclerViewTreeHook
import app.morphe.patches.youtube.misc.recyclerviewtree.hook.recyclerViewTreeHookPatch
import app.morphe.patches.youtube.misc.settings.settingsPatch

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/youtube/patches/ChangeShareSheetPatch;"

private const val FILTER_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/youtube/patches/components/ChangeShareSheetFilter;"

@Suppress("unused")
internal fun changeShareSheetPatch(
) = bytecodePatch(
    name = "Change share sheet",
    description = "Adds an option to change the in-app share sheet to the system share sheet."
) {

    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        lithoFilterPatch,
        recyclerViewTreeHookPatch
    )

    execute {
        PreferenceScreen.MISC.addPreferences(
            SwitchPreference("morphe_change_share_sheet")
        )

        addRecyclerViewTreeHook(EXTENSION_CLASS_DESCRIPTOR)

        QueryIntentListFingerprint.method.apply {

            addInstructions(
                0,
                """
                invoke-static {}, $EXTENSION_CLASS_DESCRIPTOR->changeShareSheetEnabled()Z
                move-result v0
                if-eqz v0, :ignore
                new-instance v0, Ljava/util/ArrayList;
                invoke-direct {v0}, Ljava/util/ArrayList;-><init>()V
                return-object v0
                :ignore
                nop
                """
            )
        }

        addLithoFilter(FILTER_CLASS_DESCRIPTOR)
    }
}