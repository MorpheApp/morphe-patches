package app.morphe.patches.youtube.interaction.copyvideolink

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.shared.misc.settings.preference.noTitleUnsortedPreferenceCategory
import app.morphe.patches.youtube.layout.buttons.overlay.addPlayerOverlayPreferences
import app.morphe.patches.youtube.layout.buttons.overlay.playerOverlayButtonsSettingsPatch
import app.morphe.patches.youtube.layout.player.buttons.addPlayerBottomButton
import app.morphe.patches.youtube.layout.player.buttons.playerOverlayButtonsHookPatch
import app.morphe.patches.youtube.layout.player.icons.copyPlayerButtonIcons
import app.morphe.patches.youtube.layout.player.icons.playerIconStylePatch
import app.morphe.patches.youtube.misc.playercontrols.addLegacyBottomControl
import app.morphe.patches.youtube.misc.playercontrols.initializeLegacyBottomControl
import app.morphe.patches.youtube.misc.playercontrols.legacyPlayerControlsPatch
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patches.youtube.video.information.videoInformationPatch

private const val EXTENSION_BUTTON = "Lapp/morphe/extension/youtube/videoplayer/CopyVideoLinkButton;"

private val copyVideoLinkButtonResourcePatch = resourcePatch {
    dependsOn(
        settingsPatch,
        legacyPlayerControlsPatch,
        playerIconStylePatch
    )

    execute {
        copyPlayerButtonIcons("copyvideolinkbutton", "morphe_yt_copy", "morphe_yt_copy_timestamp")

        addLegacyBottomControl("copyvideolinkbutton")
    }
}

@Suppress("unused")
val copyVideoLinkButtonPatch = bytecodePatch(
    name = "Copy video link",
    description = "Adds options to display buttons in the video player to copy video links.",
) {
    dependsOn(
        copyVideoLinkButtonResourcePatch,
        playerOverlayButtonsSettingsPatch,
        playerOverlayButtonsHookPatch,
        legacyPlayerControlsPatch,
        videoInformationPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        addPlayerOverlayPreferences(
            noTitleUnsortedPreferenceCategory(
                SwitchPreference("morphe_copy_video_link_button", summary = true),
                SwitchPreference("morphe_copy_video_link_with_timestamp_button", summary = true)
            )
        )

        addPlayerBottomButton(EXTENSION_BUTTON)

        initializeLegacyBottomControl(EXTENSION_BUTTON)
    }
}
