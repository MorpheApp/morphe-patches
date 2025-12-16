package app.morphe.patches.youtube.video.speed.button

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playercontrols.addBottomControl
import app.morphe.patches.youtube.misc.playercontrols.initializeBottomControl
import app.morphe.patches.youtube.misc.playercontrols.injectVisibilityCheckCall
import app.morphe.patches.youtube.misc.playercontrols.playerControlsPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.video.information.userSelectedPlaybackSpeedHook
import app.morphe.patches.youtube.video.information.videoInformationPatch
import app.morphe.patches.youtube.video.information.videoSpeedChangedHook
import app.morphe.patches.youtube.video.speed.custom.customPlaybackSpeedPatch
import app.morphe.util.ResourceGroup
import app.morphe.util.copyResources

private val playbackSpeedButtonResourcePatch = resourcePatch {
    dependsOn(playerControlsPatch)

    execute {
        copyResources(
            "speedbutton",
            ResourceGroup(
                "drawable",
                "morphe_playback_speed_dialog_button_rectangle.xml"
            )
        )

        addBottomControl("speedbutton")
    }
}

private const val SPEED_BUTTON_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/youtube/videoplayer/PlaybackSpeedDialogButton;"

val playbackSpeedButtonPatch = bytecodePatch(
    description = "Adds the option to display playback speed dialog button in the video player.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        customPlaybackSpeedPatch,
        playbackSpeedButtonResourcePatch,
        playerControlsPatch,
        videoInformationPatch,
    )

    execute {
        PreferenceScreen.PLAYER.addPreferences(
            SwitchPreference("morphe_playback_speed_dialog_button"),
        )

        initializeBottomControl(SPEED_BUTTON_CLASS_DESCRIPTOR)
        injectVisibilityCheckCall(SPEED_BUTTON_CLASS_DESCRIPTOR)

        videoSpeedChangedHook(SPEED_BUTTON_CLASS_DESCRIPTOR, "videoSpeedChanged")
        userSelectedPlaybackSpeedHook(SPEED_BUTTON_CLASS_DESCRIPTOR, "videoSpeedChanged")
    }
}
