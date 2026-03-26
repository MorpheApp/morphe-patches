package app.morphe.patches.youtube.interaction.copyvideourl

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.shared.misc.settings.preference.PreferenceCategory
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference.Sorting
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.layout.playerbuttons.addPlayerBottomButton
import app.morphe.patches.youtube.layout.playerbuttons.playerOverlayButtonsHookPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patches.youtube.video.information.videoInformationPatch
import app.morphe.util.ResourceGroup
import app.morphe.util.copyResources

private const val EXTENSION_CLASS_DESCRIPTOR = "Lapp/morphe/extension/youtube/videoplayer/CopyVideoURLButton;"

private val copyVideoURLResourcePatch = resourcePatch {
    dependsOn(
        settingsPatch,
        playerOverlayButtonsHookPatch
    )

    execute {
        PreferenceScreen.PLAYER.addPreferences(
            PreferenceCategory(
                titleKey = null,
                sorting = Sorting.UNSORTED,
                tag = "app.morphe.extension.shared.settings.preference.NoTitlePreferenceCategory",
                preferences = setOf(
                    SwitchPreference("morphe_copy_video_url"),
                    SwitchPreference("morphe_copy_video_url_timestamp")
                )
            )
        )

        copyResources(
            "copyvideourl",
            ResourceGroup(
                resourceDirectoryName = "drawable",
                "morphe_yt_copy.xml",
                "morphe_yt_copy_timestamp.xml"
            )
        )
    }
}

@Suppress("unused")
val copyVideoURLPatch = bytecodePatch(
    name = "Copy video URL",
    description = "Adds options to display buttons in the video player to copy video URLs.",
) {
    dependsOn(
        copyVideoURLResourcePatch,
        playerOverlayButtonsHookPatch,
        videoInformationPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        addPlayerBottomButton(EXTENSION_CLASS_DESCRIPTOR)
    }
}
