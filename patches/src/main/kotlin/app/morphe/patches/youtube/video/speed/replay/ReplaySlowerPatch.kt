/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5006
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.video.speed.replay

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.patch
import app.morphe.patches.shared.misc.settings.preference.InputType
import app.morphe.patches.shared.misc.settings.preference.NonInteractivePreference
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.shared.misc.settings.preference.TextPreference
import app.morphe.patches.shared.misc.settings.preference.noTitleUnsortedPreferenceCategory
import app.morphe.patches.youtube.interaction.swipecontrols.swipeControlsPatch
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
import app.morphe.patches.youtube.video.information.onCreateHook
import app.morphe.patches.youtube.video.information.videoInformationPatch
import app.morphe.util.setExtensionIsPatchIncluded

private const val EXTENSION_CLASS = "Lapp/morphe/extension/youtube/videoplayer/ReplayVideoButton;"

@Suppress("unused")
val replaySlowerPatch = patch(
    name = "Replay slower button",
    description = "Adds options to rewind and play part of the video at a slower speed, with a player button or by swiping left on the video.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        videoInformationPatch,
        playerOverlayButtonsSettingsPatch,
        playerOverlayButtonsHookPatch,
        legacyPlayerControlsPatch,
        swipeControlsPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        setExtensionIsPatchIncluded(EXTENSION_CLASS)
        addPlayerOverlayPreferences(
            noTitleUnsortedPreferenceCategory(
                SwitchPreference("morphe_replay_video_button", summary = true),
                SwitchPreference("morphe_replay_video_swipe", summary = true),
                NonInteractivePreference(
                    "morphe_replay_video_seconds",
                    tag = "app.morphe.extension.shared.settings.preference.SeekBarPreference",
                    selectable = true
                ),
                NonInteractivePreference(
                    "morphe_replay_video_slow_seconds",
                    tag = "app.morphe.extension.shared.settings.preference.SeekBarPreference",
                    selectable = true
                ),
                TextPreference("morphe_replay_video_speed", inputType = InputType.NUMBER_DECIMAL),
            )
        )

        addPlayerBottomButton(EXTENSION_CLASS)
        initializeLegacyBottomControl(EXTENSION_CLASS)

        // A seek by the user ends the replay.
        listOf(
            SeekFingerprint,
            SeekRelativeFingerprint
        ).forEach {
            it.method.addInstruction(0, "invoke-static { }, $EXTENSION_CLASS->onSeek()V")
        }

        // Restore the speed before the next video plays.
        onCreateHook(EXTENSION_CLASS, "newVideoStarted")

        copyPlayerButtonIcons("replayvideobutton", "morphe_replay_video_button", "morphe_replay_video_button_active")

        addLegacyBottomControl("replayvideobutton")
    }
}
