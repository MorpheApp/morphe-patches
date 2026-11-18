/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5006
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.video.speed.replay

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.classDefBy
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.patch.patch
import app.morphe.patches.shared.misc.settings.preference.InputType
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.shared.misc.settings.preference.TextPreference
import app.morphe.patches.shared.misc.settings.preference.noTitleUnsortedPreferenceCategory
import app.morphe.patches.youtube.layout.buttons.overlay.addPlayerOverlayPreferences
import app.morphe.patches.youtube.layout.buttons.overlay.playerOverlayButtonsSettingsPatch
import app.morphe.patches.youtube.layout.player.buttons.addPlayerBottomButton
import app.morphe.patches.youtube.layout.player.buttons.playerOverlayButtonsHookPatch
import app.morphe.patches.youtube.layout.player.icons.copyPlayerButtonIcons
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playercontrols.addLegacyBottomControl
import app.morphe.patches.youtube.misc.playercontrols.initializeLegacyBottomControl
import app.morphe.patches.youtube.misc.playercontrols.legacyPlayerControlsPatch
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patches.youtube.video.information.SeekFingerprint
import app.morphe.patches.youtube.video.information.SeekRelativeFingerprint
import app.morphe.patches.youtube.video.information.userSelectedPlaybackSpeedHook
import app.morphe.patches.youtube.video.information.videoInformationPatch
import app.morphe.patches.youtube.video.information.videoSpeedChangedHook
import app.morphe.patches.youtube.video.information.EXTENSION_CLASS as VIDEO_INFORMATION

private const val EXTENSION_CLASS = "Lapp/morphe/extension/youtube/videoplayer/ReplayVideoButton;"

private val replayVideoResourcePatch = patch {
    dependsOn(settingsPatch, legacyPlayerControlsPatch)
    execute {
        copyPlayerButtonIcons("replayvideobutton", "morphe_replay_video_button", "morphe_replay_video_button_active")
        addLegacyBottomControl("replayvideobutton")
    }
}

val replayVideoPatch = patch(
    name = "Replay video slower",
    description = "Adds a player button to rewind and play a configurable part of a video at a slower speed.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        videoInformationPatch,
        playerOverlayButtonsSettingsPatch,
        playerOverlayButtonsHookPatch,
        legacyPlayerControlsPatch,
        replayVideoResourcePatch,
    )
    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        addPlayerOverlayPreferences(
            noTitleUnsortedPreferenceCategory(
                SwitchPreference("morphe_replay_video_button", summary = true),
                TextPreference("morphe_replay_video_seconds", inputType = InputType.NUMBER),
                TextPreference("morphe_replay_video_slow_seconds", inputType = InputType.NUMBER),
                TextPreference("morphe_replay_video_speed", inputType = InputType.NUMBER_DECIMAL),
            )
        )
        addPlayerBottomButton(EXTENSION_CLASS)
        initializeLegacyBottomControl(EXTENSION_CLASS)

        // Both native seek methods are already identified by VideoInformation.
        listOf(SeekFingerprint, SeekRelativeFingerprint).forEach {
            it.clearMatch()
            it.method.addInstruction(0, "invoke-static { }, $EXTENSION_CLASS->onSeek()V")
        }
        videoSpeedChangedHook(EXTENSION_CLASS, "onSpeedChanged")
        userSelectedPlaybackSpeedHook(EXTENSION_CLASS, "onUserSelectedSpeed")

        // Native menus use the shared selection hook; Morphe's menu calls the extension directly.
        mutableClassDefBy(classDefBy(VIDEO_INFORMATION)).methods.single { it.name == "setPlaybackSpeed" }
            .addInstruction(0, "invoke-static { p0 }, $EXTENSION_CLASS->onUserSelectedSpeed(F)V")
        // Restore the old player's speed before its identity/controller is replaced.
        mutableClassDefBy(classDefBy(VIDEO_INFORMATION)).methods.single { it.name == "initialize" }
            .addInstruction(0, "invoke-static { }, $EXTENSION_CLASS->onVideoReset()V")
    }
}
