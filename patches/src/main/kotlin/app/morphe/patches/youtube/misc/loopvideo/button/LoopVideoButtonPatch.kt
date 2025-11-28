package app.morphe.patches.youtube.misc.loopvideo.button

import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.patch.resourcePatch
import app.morphe.patches.all.misc.resources.addResources
import app.morphe.patches.all.misc.resources.addResourcesPatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playercontrols.*
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
                "revanced_loop_video_button_on.xml",
                "revanced_loop_video_button_off.xml"
            )
        )

        addBottomControl("loopvideobutton")
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
        addResourcesPatch,
        loopVideoButtonResourcePatch,
        playerControlsPatch,
    )

    execute {
        addResources("youtube", "misc.loopvideo.button.loopVideoButtonPatch")

        PreferenceScreen.PLAYER.addPreferences(
            SwitchPreference("revanced_loop_video_button"),
        )

        // Initialize the button using standard approach.
        initializeBottomControl(LOOP_VIDEO_BUTTON_CLASS_DESCRIPTOR)
        injectVisibilityCheckCall(LOOP_VIDEO_BUTTON_CLASS_DESCRIPTOR)
    }
}
