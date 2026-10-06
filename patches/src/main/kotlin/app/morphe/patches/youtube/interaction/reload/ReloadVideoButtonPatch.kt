/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.interaction.reload

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.layout.buttons.overlay.addPlayerOverlayPreferences
import app.morphe.patches.youtube.layout.buttons.overlay.playerOverlayButtonsSettingsPatch
import app.morphe.patches.youtube.layout.player.icons.copyPlayerButtonIcons
import app.morphe.patches.youtube.misc.loadvideo.loadVideoHookPatch
import app.morphe.patches.youtube.misc.playercontrols.addTopControl
import app.morphe.patches.youtube.misc.playercontrols.initializeTopControl
import app.morphe.patches.youtube.misc.playercontrols.legacyPlayerControlsPatch
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patches.youtube.video.information.videoInformationPatch

private val reloadVideoButtonResourcePatch = resourcePatch {
    dependsOn(
        settingsPatch,
        legacyPlayerControlsPatch
    )

    execute {
        copyPlayerButtonIcons("reloadbutton", "morphe_reload_video_button")
    }
}

private const val EXTENSION_BUTTON =
    "Lapp/morphe/extension/youtube/videoplayer/ReloadVideoButton;"

@Suppress("unused")
val reloadVideoButtonPatch = bytecodePatch(
    name = "Reload video",
    description = "Adds an option to display reload video button in the video player.",
) {
    dependsOn(
        reloadVideoButtonResourcePatch,
        legacyPlayerControlsPatch,
        videoInformationPatch,
        playerOverlayButtonsSettingsPatch,
        loadVideoHookPatch,
        bytecodePatch {
            finalize {
                addTopControl(
                    "reloadbutton",
                    "@+id/morphe_reload_video_button",
                    "@+id/morphe_reload_video_button"
                )
            }
        }
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        addPlayerOverlayPreferences(
            SwitchPreference("morphe_reload_video_button")
        )

        initializeTopControl(EXTENSION_BUTTON)
    }
}
