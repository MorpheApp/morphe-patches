/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3942
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.interaction.searchcomments

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.util.cloneParameters
import app.morphe.patches.all.misc.resources.addResourcesPatch
import app.morphe.patches.shared.misc.bridge.addBridge
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
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.copyResources
import app.morphe.util.findInstructionIndicesReversedOrThrow
import com.android.tools.smali.dexlib2.Opcode

private const val EXTENSION_FILTER =
    "Lapp/morphe/extension/youtube/patches/components/CommentsSearchFilter;"

private const val SECTION_LIST_ACCESS_INTERFACE =
    $$"Lapp/morphe/extension/youtube/patches/components/CommentsSearchFilter$SectionListAccess;"

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

        // The search button is added next to the close button of the panel header. The header
        // of a panel with no subheader, such as the comments of Shorts, is set up without
        // reaching the end of the method, so every return is hooked. Panels reuse their header,
        // so the button is shown or hidden for every panel.
        PanelSubheaderFingerprint.method.cloneParameters().apply {
            findInstructionIndicesReversedOrThrow(Opcode.RETURN_VOID).forEach { index ->
                addInstructionsAtControlFlowLabel(
                    index,
                    "invoke-static/range { p0 .. p0 }, $EXTENSION_FILTER->onPanelHeaderSetUp(Landroid/view/View;)V"
                )
            }
        }

        // The comments are reloaded after a search, as when the sort order is changed,
        // so the search applies to all of them.
        val refreshMatches = SectionListRefreshFingerprint.instructionMatches
        val reloadContinuationType = refreshMatches.first().fieldReference
        val hasContinuationMethod = refreshMatches[1].getMethodCalled()
        val reloadMethod = refreshMatches.last().getMethodCalled()
        val listViewField = SectionListControllerConstructorFingerprint.instructionMatches.first().fieldReference

        mutableClassDefBy(listViewField.definingClass).apply {
            interfaces.add(SECTION_LIST_ACCESS_INTERFACE)
            addBridge(
                "patch_getListView", listOf(), "Landroid/view/View;", 2,
                """
                    iget-object v0, p0, $listViewField
                    return-object v0
                """
            )
            addBridge(
                "patch_reload", listOf(), "Z", 2,
                """
                    sget-object v0, $reloadContinuationType
                    invoke-virtual { p0, v0 }, $hasContinuationMethod
                    move-result v0
                    if-eqz v0, :no_reload_continuation
                    invoke-virtual { p0 }, $reloadMethod
                    :no_reload_continuation
                    return v0
                """
            )
        }

        SectionListControllerConstructorFingerprint.let {
            it.method.apply {
                addInstruction(
                    it.instructionMatches.last().index,
                    "invoke-static/range { p0 .. p0 }, $EXTENSION_FILTER->onSectionListCreated($SECTION_LIST_ACCESS_INTERFACE)V"
                )
            }
        }
    }
}
