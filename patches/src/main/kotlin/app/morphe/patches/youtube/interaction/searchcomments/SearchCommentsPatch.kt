/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.interaction.searchcomments

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.all.misc.resources.addResourcesPatch
import app.morphe.patches.shared.misc.litho.filter.addLithoFilter
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.engagement.engagementPanelHookPatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.litho.filter.lithoFilterPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patches.youtube.video.information.videoInformationPatch
import app.morphe.util.ResourceGroup
import app.morphe.util.copyResources
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstructionOrThrow
import app.morphe.util.indexOfFirstInstructionReversedOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val EXTENSION_FILTER =
    "Lapp/morphe/extension/youtube/patches/components/CommentsSearchFilter;"

@Suppress("unused")
val searchCommentsPatch = bytecodePatch(
    name = "Search comments",
    description = "Adds a button to the comments to show only the loaded comments that contain the searched words."
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        addResourcesPatch,
        lithoFilterPatch,
        engagementPanelHookPatch,
        videoInformationPatch,
        resourcePatch {
            execute {
                copyResources(
                    "searchcomments",
                    ResourceGroup("drawable", "morphe_search_comments_button.xml")
                )
            }
        }
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.GENERAL.addPreferences(
            SwitchPreference("morphe_search_comments", summary = true)
        )

        addLithoFilter(EXTENSION_FILTER)

        // The search button is added next to the close button of the comments panel.
        PanelSubheaderFingerprint.let {
            it.method.apply {
                val removeAllViewsIndex = indexOfFirstInstructionReversedOrThrow {
                    opcode == Opcode.INVOKE_VIRTUAL &&
                            getReference<MethodReference>()?.name == "removeAllViews"
                }

                val setVisibilityIndex = indexOfFirstInstructionOrThrow(removeAllViewsIndex) {
                    opcode == Opcode.INVOKE_VIRTUAL &&
                            getReference<MethodReference>()?.name == "setVisibility"
                }

                val subheaderRegister = getInstruction<FiveRegisterInstruction>(setVisibilityIndex).registerC

                addInstruction(
                    setVisibilityIndex + 1,
                    "invoke-static { v$subheaderRegister }, $EXTENSION_FILTER->onPanelSubheaderShown(Landroid/view/View;)V"
                )
            }
        }
    }
}
