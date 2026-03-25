package app.morphe.patches.youtube.interaction.loop

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.layout.playerbuttons.addPlayerBottomButton
import app.morphe.patches.youtube.layout.playerbuttons.playerOverlayButtonsHookPatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playercontrols.playerControlsResourcePatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.util.ResourceGroup
import app.morphe.util.copyResources

private val loopVideoButtonResourcePatch = resourcePatch {
    dependsOn(playerControlsResourcePatch)

    execute {
        copyResources(
            "loopvideobutton",
            ResourceGroup(
                "drawable",
                "morphe_loop_video_button_on.xml",
                "morphe_loop_video_button_off.xml"
            )
        )
    }
}

private const val LOOP_VIDEO_BUTTON_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/youtube/videoplayer/LoopVideoButton;"

internal val loopVideoButtonPatch = bytecodePatch(
    description = "Adds the option to display loop video button in the video player.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        loopVideoButtonResourcePatch,
        playerOverlayButtonsHookPatch
    )

    execute {
        PreferenceScreen.PLAYER.addPreferences(
            SwitchPreference("morphe_loop_video_button"),
        )

        addPlayerBottomButton(LOOP_VIDEO_BUTTON_CLASS_DESCRIPTOR)
    }
}
