/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.video.speed.button

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.layout.buttons.overlay.addPlayerOverlayPreferences
import app.morphe.patches.youtube.layout.buttons.overlay.playerOverlayButtonsSettingsPatch
import app.morphe.patches.youtube.layout.player.buttons.addPlayerBottomButton
import app.morphe.patches.youtube.layout.player.buttons.playerOverlayButtonsHookPatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playercontrols.addLegacyBottomControl
import app.morphe.patches.youtube.misc.playercontrols.initializeLegacyBottomControl
import app.morphe.patches.youtube.misc.playercontrols.legacyPlayerControlsPatch
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.video.information.userSelectedPlaybackSpeedHook
import app.morphe.patches.youtube.video.information.videoInformationPatch
import app.morphe.patches.youtube.video.information.videoSpeedChangedHook
import app.morphe.patches.youtube.video.speed.custom.customPlaybackSpeedPatch

private val playbackSpeedButtonResourcePatch = resourcePatch {
    dependsOn(legacyPlayerControlsPatch)

    execute {
        addLegacyBottomControl("speedbutton")
    }
}

private const val EXTENSION_BUTTON =
    "Lapp/morphe/extension/youtube/videoplayer/PlaybackSpeedDialogButton;"

val playbackSpeedButtonPatch = bytecodePatch(
    description = "Adds the option to display playback speed dialog button in the video player.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        playerOverlayButtonsSettingsPatch,
        customPlaybackSpeedPatch,
        playbackSpeedButtonResourcePatch,
        playerOverlayButtonsHookPatch,
        videoInformationPatch,
    )

    execute {
        addPlayerOverlayPreferences(
            SwitchPreference("morphe_playback_speed_dialog_button", summary = true)
        )

        addPlayerBottomButton(EXTENSION_BUTTON)

        initializeLegacyBottomControl(EXTENSION_BUTTON)

        videoSpeedChangedHook(EXTENSION_BUTTON, "videoSpeedChanged")
        userSelectedPlaybackSpeedHook(EXTENSION_BUTTON, "videoSpeedChanged")
    }
}
