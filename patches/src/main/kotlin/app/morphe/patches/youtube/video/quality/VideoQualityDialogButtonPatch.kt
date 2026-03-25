package app.morphe.patches.youtube.video.quality

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.layout.playerbuttons.addPlayerBottomButton
import app.morphe.patches.youtube.layout.playerbuttons.playerOverlayButtonsHookPatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.util.ResourceGroup
import app.morphe.util.copyResources

private val videoQualityButtonResourcePatch = resourcePatch {
    execute {
        copyResources(
            "qualitybutton",
            ResourceGroup(
                "drawable",
                "morphe_video_quality_dialog_button_rectangle.xml",
            ),
        )
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
        playerOverlayButtonsHookPatch
    )

    execute {
        PreferenceScreen.PLAYER.addPreferences(
            SwitchPreference("morphe_video_quality_dialog_button"),
        )

        addPlayerBottomButton(QUALITY_BUTTON_CLASS_DESCRIPTOR)
    }
}
