package app.morphe.patches.youtube.video.quality

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
import app.morphe.util.ResourceGroup
import app.morphe.util.copyResources

private val videoQualityButtonResourcePatch = resourcePatch {
    dependsOn(playerControlsPatch)

    execute {
        copyResources(
            "qualitybutton",
            ResourceGroup(
                "drawable",
                "morphe_video_quality_dialog_button_rectangle.xml",
            ),
        )

        addBottomControl("qualitybutton")
    }
}

private const val QUALITY_BUTTON_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/youtube/videoplayer/VideoQualityDialogButton;"

val videoQualityDialogButtonPatch = bytecodePatch(
    description = "Adds the option to display video quality dialog button in the video player.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        rememberVideoQualityPatch,
        videoQualityButtonResourcePatch,
        playerControlsPatch,
    )

    execute {
        PreferenceScreen.PLAYER.addPreferences(
            SwitchPreference("morphe_video_quality_dialog_button"),
        )

        initializeBottomControl(QUALITY_BUTTON_CLASS_DESCRIPTOR)
        injectVisibilityCheckCall(QUALITY_BUTTON_CLASS_DESCRIPTOR)
    }
}
