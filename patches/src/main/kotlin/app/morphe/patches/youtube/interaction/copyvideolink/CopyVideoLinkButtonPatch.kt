package app.morphe.patches.youtube.interaction.copyvideolink

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.shared.misc.settings.preference.PreferenceCategory
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference.Sorting
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.layout.buttons.overlay.addPlayerOverlayPreferences
import app.morphe.patches.youtube.layout.buttons.overlay.playerOverlayButtonsSettingsPatch
import app.morphe.patches.youtube.layout.player.buttons.addPlayerBottomButton
import app.morphe.patches.youtube.layout.player.buttons.playerOverlayButtonsHookPatch
import app.morphe.patches.youtube.misc.playercontrols.addLegacyBottomControl
import app.morphe.patches.youtube.misc.playercontrols.initializeLegacyBottomControl
import app.morphe.patches.youtube.misc.playercontrols.injectVisibilityCheckCall
import app.morphe.patches.youtube.misc.playercontrols.legacyPlayerControlsPatch
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patches.youtube.video.information.videoInformationPatch
import app.morphe.util.ResourceGroup
import app.morphe.util.copyResources

private const val EXTENSION_BUTTON = "Lapp/morphe/extension/youtube/videoplayer/CopyVideoLinkButton;"

private val copyVideoLinkButtonResourcePatch = resourcePatch {
    dependsOn(
        settingsPatch,
        legacyPlayerControlsPatch
    )

    execute {
        copyResources(
            "copyvideolinkbutton",
            ResourceGroup(
                resourceDirectoryName = "drawable",
                "morphe_yt_copy.xml",
                "morphe_yt_copy_timestamp.xml",
                "morphe_yt_copy_bold.xml",
                "morphe_yt_copy_timestamp_bold.xml"
            )
        )

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
            PreferenceCategory(
                titleKey = null,
                sorting = Sorting.UNSORTED,
                tag = "app.morphe.extension.shared.settings.preference.NoTitlePreferenceCategory",
                preferences = setOf(
                    SwitchPreference("morphe_copy_video_link_button", summary = true),
                    SwitchPreference("morphe_copy_video_link_with_timestamp_button", summary = true)
                )
            )
        )

        addPlayerBottomButton(EXTENSION_BUTTON)

        initializeLegacyBottomControl(EXTENSION_BUTTON)
        injectVisibilityCheckCall(EXTENSION_BUTTON)
    }
}
