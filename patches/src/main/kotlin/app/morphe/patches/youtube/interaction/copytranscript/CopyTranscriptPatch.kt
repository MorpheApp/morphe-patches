/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5226
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.interaction.copytranscript

import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.cloneParameters
import app.morphe.patches.all.misc.resources.addResourcesPatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.layout.captions.settingsMenuCaptionGroup
import app.morphe.patches.youtube.layout.flyout.flyoutPatch
import app.morphe.patches.youtube.misc.engagement.engagementPanelHookPatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patches.youtube.video.information.videoInformationPatch
import app.morphe.util.ResourceGroup
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.copyResources
import app.morphe.util.findInstructionIndicesReversedOrThrow
import app.morphe.util.setExtensionIsPatchIncluded
import com.android.tools.smali.dexlib2.Opcode

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/CopyTranscriptPatch;"

val copyTranscriptPatch = patch(
    name = "Copy transcript",
    description = "Adds a button to the transcript panel that copies the transcript " +
            "in the user's current caption language. Timestamps are optional via settings. " +
            "Optionally adds the same action to Shorts flyout menus (off by default)."
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        addResourcesPatch,
        engagementPanelHookPatch,
        videoInformationPatch,
        flyoutPatch,
        patch {
            execute {
                copyResources(
                    "copytranscript",
                    ResourceGroup(
                        "drawable",
                        "morphe_copy_transcript_button.xml"
                    )
                )
            }
        }
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        settingsMenuCaptionGroup.addAll(
            listOf(
                SwitchPreference("morphe_copy_transcript", summary = true),
                SwitchPreference("morphe_copy_transcript_timestamps", summary = true),
                SwitchPreference("morphe_copy_transcript_shorts_flyout", summary = true),
            )
        )

        setExtensionIsPatchIncluded(EXTENSION_CLASS)

        // The copy button is added next to the close button of the panel header.
        // Panels reuse their header, so the button is shown or hidden for every panel.
        PanelSubheaderFingerprint.method.cloneParameters().apply {
            findInstructionIndicesReversedOrThrow(Opcode.RETURN_VOID).forEach { index ->
                addInstructionsAtControlFlowLabel(
                    index,
                    "invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS->" +
                            "onPanelHeaderSetUp(Landroid/view/View;)V"
                )
            }
        }
    }
}
