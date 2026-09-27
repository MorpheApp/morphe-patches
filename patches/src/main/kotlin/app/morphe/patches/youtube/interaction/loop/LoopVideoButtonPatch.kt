package app.morphe.patches.youtube.interaction.loop

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.layout.buttons.overlay.addPlayerOverlayPreferences
import app.morphe.patches.youtube.layout.buttons.overlay.playerOverlayButtonsSettingsPatch
import app.morphe.patches.youtube.layout.player.buttons.playerOverlayButtonsHookPatch
import app.morphe.patches.youtube.layout.player.icons.copyPlayerButtonIcons
import app.morphe.patches.youtube.layout.player.icons.playerIconStylePatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playercontrols.addTopControl
import app.morphe.patches.youtube.misc.playercontrols.initializeTopControl
import app.morphe.patches.youtube.misc.playercontrols.legacyPlayerControlsPatch
import app.morphe.patches.youtube.misc.playercontrols.legacyPlayerControlsResourcePatch
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.StartVideoInformerFingerprint

private val loopVideoButtonResourcePatch = resourcePatch {
    dependsOn(
        legacyPlayerControlsResourcePatch,
        playerIconStylePatch
    )

    execute {
        copyPlayerButtonIcons(
            "loopvideobutton",
            "morphe_loop_video_button_on",
            "morphe_loop_video_button_off",
            "morphe_loop_video_button_range"
        )
    }

    finalize {
        addTopControl(
            "loopvideobutton",
            "@+id/morphe_loop_video_button",
            "@+id/morphe_loop_video_button"
        )
    }
}

private const val EXTENSION_BUTTON =
    "Lapp/morphe/extension/youtube/videoplayer/LoopVideoButton;"

internal val loopVideoButtonPatch = bytecodePatch(
    description = "Adds an option to display loop video button in the video player."
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        loopVideoButtonResourcePatch,
        playerOverlayButtonsSettingsPatch,
        legacyPlayerControlsPatch,
        playerOverlayButtonsHookPatch
    )

    execute {
        addPlayerOverlayPreferences(
            SwitchPreference("morphe_loop_video_button")
        )

        initializeTopControl(EXTENSION_BUTTON)
        StartVideoInformerFingerprint.method.addInstruction(
            0,
            "invoke-static { }, $EXTENSION_BUTTON->resetLoopButton()V"
        )
    }
}
