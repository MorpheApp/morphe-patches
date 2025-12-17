package app.morphe.patches.youtube.interaction.copyvideourl

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.playercontrols.addBottomControl
import app.morphe.patches.youtube.misc.playercontrols.initializeBottomControl
import app.morphe.patches.youtube.misc.playercontrols.injectVisibilityCheckCall
import app.morphe.patches.youtube.misc.playercontrols.playerControlsPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.video.information.videoInformationPatch
import app.morphe.util.ResourceGroup
import app.morphe.util.copyResources

private val copyVideoUrlResourcePatch = resourcePatch {
    dependsOn(
        settingsPatch,
        playerControlsPatch,
    )

    execute {
        PreferenceScreen.PLAYER.addPreferences(
            SwitchPreference("morphe_copy_video_url"),
            SwitchPreference("morphe_copy_video_url_timestamp"),
        )

        copyResources(
            "copyvideourl",
            ResourceGroup(
                resourceDirectoryName = "drawable",
                "morphe_yt_copy.xml",
                "morphe_yt_copy_timestamp.xml",
            ),
        )

        addBottomControl("copyvideourl")
    }
}

@Suppress("unused")
val copyVideoUrlPatch = bytecodePatch(
    name = "Copy video URL",
    description = "Adds options to display buttons in the video player to copy video URLs.",
) {
    dependsOn(
        copyVideoUrlResourcePatch,
        playerControlsPatch,
        videoInformationPatch,
    )

    compatibleWith(
        "com.google.android.youtube"(
            "20.14.43",
            "20.21.37",
            "20.31.42",
            "20.46.41",
        )
    )

    execute {
        val extensionPlayerPackage = "Lapp/morphe/extension/youtube/videoplayer"
        val buttonsDescriptors = listOf(
            "$extensionPlayerPackage/CopyVideoUrlButton;",
            "$extensionPlayerPackage/CopyVideoUrlTimestampButton;",
        )

        buttonsDescriptors.forEach { descriptor ->
            initializeBottomControl(descriptor)
            injectVisibilityCheckCall(descriptor)
        }
    }
}
