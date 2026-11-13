/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4662
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.interaction.playlistpanel

import app.morphe.patcher.patch.patch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.layout.buttons.overlay.addPlayerOverlayPreferences
import app.morphe.patches.youtube.layout.buttons.overlay.playerOverlayButtonsSettingsPatch
import app.morphe.patches.youtube.misc.commandrouter.commandRouterHookPatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playercontrols.addTopControl
import app.morphe.patches.youtube.misc.playercontrols.initializeTopControl
import app.morphe.patches.youtube.misc.playercontrols.legacyPlayerControlsPatch
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patches.youtube.video.information.videoInformationPatch

private val playlistPanelButtonResourcePatch = patch {
    dependsOn(
        settingsPatch,
        legacyPlayerControlsPatch,
    )

    finalize {
        // The icon is the app's own playlist icon, so no icons are copied.
        addTopControl(
            "playlistpanelbutton",
            "@+id/morphe_playlist_panel_button",
            "@+id/morphe_playlist_panel_button",
        )
    }
}

private const val EXTENSION_BUTTON = "Lapp/morphe/extension/youtube/videoplayer/PlaylistPanelButton;"

@Suppress("unused")
val playlistPanelButtonPatch = patch(
    name = "Playlist button",
    description = "Adds an option to display a button in the video player that opens the playlist panel.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        playlistPanelButtonResourcePatch,
        playerOverlayButtonsSettingsPatch,
        videoInformationPatch,
        commandRouterHookPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        addPlayerOverlayPreferences(
            SwitchPreference("morphe_playlist_panel_button", summary = true),
        )

        initializeTopControl(EXTENSION_BUTTON)
    }
}
