package app.morphe.patches.youtube.interaction.downloads

import app.revanced.patcher.extensions.InstructionExtensions.addInstruction
import app.revanced.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.patch.resourcePatch
import app.morphe.patches.all.misc.resources.addResources
import app.morphe.patches.all.misc.resources.addResourcesPatch
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference.Sorting
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.shared.misc.settings.preference.TextPreference
import app.morphe.patches.youtube.misc.playercontrols.addBottomControl
import app.morphe.patches.youtube.misc.playercontrols.initializeBottomControl
import app.morphe.patches.youtube.misc.playercontrols.injectVisibilityCheckCall
import app.morphe.patches.youtube.misc.playercontrols.playerControlsPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.mainActivityOnCreateFingerprint
import app.morphe.patches.youtube.video.information.videoInformationPatch
import app.morphe.util.ResourceGroup
import app.morphe.util.copyResources

private val downloadsResourcePatch = resourcePatch {
    dependsOn(
        playerControlsPatch,
        settingsPatch,
        addResourcesPatch,
    )

    execute {
        addResources("youtube", "interaction.downloads.downloadsResourcePatch")

        PreferenceScreen.PLAYER.addPreferences(
            PreferenceScreenPreference(
                key = "morphe_external_downloader_screen",
                sorting = Sorting.UNSORTED,
                preferences = setOf(
                    SwitchPreference("morphe_external_downloader"),
                    SwitchPreference("morphe_external_downloader_action_button"),
                    TextPreference(
                        "morphe_external_downloader_name",
                        tag = "app.morphe.extension.youtube.settings.preference.ExternalDownloaderPreference",
                    ),
                ),
            ),
        )

        copyResources(
            "downloads",
            ResourceGroup("drawable", "morphe_yt_download_button.xml"),
        )

        addBottomControl("downloads")
    }
}

private const val EXTENSION_CLASS_DESCRIPTOR = "Lapp/morphe/extension/youtube/patches/DownloadsPatch;"

internal const val BUTTON_DESCRIPTOR = "Lapp/morphe/extension/youtube/videoplayer/ExternalDownloadButton;"

@Suppress("unused")
val downloadsPatch = bytecodePatch(
    name = "Downloads",
    description = "Adds support to download videos with an external downloader app " +
        "using the in-app download button or a video player action button.",
) {
    dependsOn(
        downloadsResourcePatch,
        playerControlsPatch,
        videoInformationPatch,
    )

    compatibleWith(
        "com.google.android.youtube"(
            "19.43.41",
            "20.14.43",
            "20.21.37",
            "20.31.42",
            "20.46.41",
        )
    )

    execute {
        initializeBottomControl(BUTTON_DESCRIPTOR)
        injectVisibilityCheckCall(BUTTON_DESCRIPTOR)

        // Main activity is used to launch downloader intent.
        mainActivityOnCreateFingerprint.method.addInstruction(
            0,
            "invoke-static/range { p0 .. p0 }, ${EXTENSION_CLASS_DESCRIPTOR}->setMainActivity(Landroid/app/Activity;)V"
        )

        offlineVideoEndpointFingerprint.method.apply {
            addInstructionsWithLabels(
                0,
                """
                    invoke-static/range {p3 .. p3}, $EXTENSION_CLASS_DESCRIPTOR->inAppDownloadButtonOnClick(Ljava/lang/String;)Z
                    move-result v0
                    if-eqz v0, :show_native_downloader
                    return-void
                    :show_native_downloader
                    nop
                """,
            )
        }
    }
}
