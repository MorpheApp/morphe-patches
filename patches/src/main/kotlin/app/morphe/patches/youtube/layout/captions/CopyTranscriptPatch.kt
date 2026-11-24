/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5226
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.captions

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.patch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.layout.flyout.flyoutPatch
import app.morphe.patches.youtube.misc.engagement.engagementPanelHookPatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.video.information.videoInformationPatch
import app.morphe.util.setExtensionIsPatchIncluded

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/CopyTranscriptPatch;"

internal val copyTranscriptPatch = patch(
    description = "Adds a button to the transcript panel that copies the transcript.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        engagementPanelHookPatch,
        videoInformationPatch,
        flyoutPatch,
    )

    execute {
        settingsMenuCaptionGroup.addAll(
            listOf(
                SwitchPreference("morphe_copy_transcript", summary = true),
                SwitchPreference("morphe_copy_transcript_timestamps", summary = true),
                SwitchPreference("morphe_copy_transcript_shorts_flyout", summary = true),
            )
        )

        setExtensionIsPatchIncluded(EXTENSION_CLASS)

        EngagementPanelHeaderFingerprint.method.addInstruction(
            0,
            "invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS->onPanelHeaderSetUp(Landroid/view/View;)V"
        )
    }
}
