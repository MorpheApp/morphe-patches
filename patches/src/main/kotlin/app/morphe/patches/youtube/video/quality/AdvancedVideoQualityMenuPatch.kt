/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.video.quality

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.all.misc.resources.ResourceType
import app.morphe.patches.all.misc.resources.getResourceId
import app.morphe.patches.all.misc.resources.resourceMappingPatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.litho.filter.addLithoFilter
import app.morphe.patches.youtube.misc.litho.filter.lithoFilterPatch
import app.morphe.patches.youtube.misc.recyclerviewtree.hook.addRecyclerViewTreeHook
import app.morphe.patches.youtube.misc.recyclerviewtree.hook.recyclerViewTreeHookPatch
import app.morphe.patches.youtube.misc.settings.settingsPatch
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/playback/quality/AdvancedVideoQualityMenuPatch;"

private const val EXTENSION_FILTER =
    "Lapp/morphe/extension/youtube/patches/components/AdvancedVideoQualityMenuFilter;"

internal val advancedVideoQualityMenuPatch = bytecodePatch {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        lithoFilterPatch,
        recyclerViewTreeHookPatch,
        resourceMappingPatch
    )

    execute {
        settingsMenuVideoQualityGroup.add(
            SwitchPreference("morphe_advanced_video_quality_menu")
        )

        // region Patch for the old type of the video quality menu.
        // Used for regular videos when spoofing to old app version,
        // and for the Shorts quality flyout on newer app versions.
        VideoQualityMenuViewInflateFingerprint.let {
            it.method.apply {
                val matches = it.instructionMatches
                val checkCastIndex = matches[matches.lastIndex - 1].index
                val listViewRegister = getInstruction<OneRegisterInstruction>(checkCastIndex).registerA

                addInstruction(
                    checkCastIndex + 1,
                    "invoke-static { v$listViewRegister }, $EXTENSION_CLASS->" +
                            "addVideoQualityListMenuListener(Landroid/widget/ListView;)V",
                )
            }
        }

        // Force YT to add the 'advanced' quality menu for Shorts.
        VideoQualityMenuOptionsFingerprint.let {
            val matches = it.instructionMatches
            val startIndex = matches.first().index
            val insertIndex = matches[matches.lastIndex - 1].index
            if (startIndex != 0) throw PatchException("Unexpected opcode start index: $startIndex")

            it.method.apply {
                val register = getInstruction<OneRegisterInstruction>(insertIndex).registerA

                // A condition controls whether to show the three or four items quality menu.
                // Force the four items quality menu to make the "Advanced" item visible, necessary for the patch.
                addInstructions(
                    insertIndex,
                    """
                        invoke-static { v$register }, $EXTENSION_CLASS->forceAdvancedVideoQualityMenuCreation(Z)Z
                        move-result v$register
                    """
                )
            }
        }

        // endregion

        // region Patch for the new type of the video quality menu.

        addRecyclerViewTreeHook(EXTENSION_CLASS)

        // Required to check if the video quality menu is currently shown in order to click on the "Advanced" item.
        addLithoFilter(EXTENSION_FILTER)

        // endregion
    }
}
