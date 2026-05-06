/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.interaction.savetowatchlater

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.layout.buttons.overlay.addPlayerOverlayPreferences
import app.morphe.patches.youtube.layout.buttons.overlay.playerOverlayButtonsSettingsPatch
import app.morphe.patches.youtube.misc.playercontrols.addTopControl
import app.morphe.patches.youtube.misc.playercontrols.initializeTopControl
import app.morphe.patches.youtube.misc.playercontrols.injectVisibilityCheckCall
import app.morphe.patches.youtube.misc.playercontrols.legacyPlayerControlsPatch
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patches.youtube.shared.YouTubeActivityOnCreateFingerprint
import app.morphe.patches.youtube.video.information.videoInformationPatch
import app.morphe.util.ResourceGroup
import app.morphe.util.copyResources

private val saveToWatchLaterButtonResourcePatch = resourcePatch {
    dependsOn(
        settingsPatch,
        legacyPlayerControlsPatch,
    )

    execute {

        copyResources(
            "savetowatchlaterbutton",
            ResourceGroup(
                resourceDirectoryName = "drawable",
                "morphe_save_to_watch_later_button.xml",
                "morphe_save_to_watch_later_button_bold.xml",
            )
        )
    }
}

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/SaveToWatchLaterPatch;"

private const val EXTENSION_BUTTON =
    "Lapp/morphe/extension/youtube/videoplayer/SaveToWatchLaterButton;"

@Suppress("unused")
val saveToWatchLaterButtonPatch = bytecodePatch(
    name = "Save to watch later",
    description = "Adds an option to display save to watch later button in the video player.",
) {
    dependsOn(
        saveToWatchLaterButtonResourcePatch,
        legacyPlayerControlsPatch,
        videoInformationPatch,
        playerOverlayButtonsSettingsPatch,
        bytecodePatch {
            finalize {
                addTopControl(
                    "savetowatchlaterbutton",
                    "@+id/morphe_save_to_watch_later_button",
                    "@+id/morphe_save_to_watch_later_button"
                )
            }
        }
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        addPlayerOverlayPreferences(
            SwitchPreference("morphe_save_to_watch_later_button")
        )

        initializeTopControl(EXTENSION_BUTTON)
        injectVisibilityCheckCall(EXTENSION_BUTTON)

        // Main activity is used to launch downloader intent.
        YouTubeActivityOnCreateFingerprint.method.addInstruction(
            0,
            "invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS->setMainActivity(Landroid/app/Activity;)V"
        )
    }
}
