/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.interaction.savetowatchlater

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.shared.misc.settings.preference.noTitleUnsortedPreferenceCategory
import app.morphe.patches.youtube.layout.buttons.overlay.addPlayerOverlayPreferences
import app.morphe.patches.youtube.layout.buttons.overlay.playerOverlayButtonsSettingsPatch
import app.morphe.patches.youtube.layout.player.icons.copyPlayerButtonIcons
import app.morphe.patches.youtube.layout.player.icons.copyPlayerIconStyles
import app.morphe.patches.youtube.layout.player.icons.playerIconStylePatch
import app.morphe.patches.youtube.misc.auth.authHookPatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playercontrols.addTopControl
import app.morphe.patches.youtube.misc.playercontrols.initializeTopControl
import app.morphe.patches.youtube.misc.playercontrols.legacyPlayerControlsPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patches.youtube.video.information.videoInformationPatch

private val saveToWatchLaterButtonResourcePatch = resourcePatch {
    dependsOn(playerIconStylePatch)

    execute {
        copyPlayerButtonIcons("savetowatchlaterbutton", "morphe_save_to_watch_later_button")
        copyPlayerIconStyles("savetowatchlaterbutton", "morphe_add_to_queue_button")
    }
}

private const val EXTENSION_BUTTON =
    "Lapp/morphe/extension/youtube/videoplayer/SaveToWatchLaterButton;"

@Suppress("unused")
val saveToWatchLaterButtonPatch = bytecodePatch(
    name = "Save to Watch later",
    description = "Adds an option to display save to Watch later button in the video player.",
) {
    dependsOn(
        saveToWatchLaterButtonResourcePatch,
        settingsPatch,
        legacyPlayerControlsPatch,
        playerOverlayButtonsSettingsPatch,
        sharedExtensionPatch,
        videoInformationPatch,
        authHookPatch,
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
            noTitleUnsortedPreferenceCategory(
                SwitchPreference("morphe_save_to_watch_later_button", summary = true),
                SwitchPreference("morphe_swap_save_and_queue_actions", summary = true),
                SwitchPreference("morphe_queue_restore", summary = true)
            )
        )

        PreferenceScreen.PLAYER.addPreferences(
            SwitchPreference("morphe_save_to_watch_later_flyout_button", summary = true),
            SwitchPreference("morphe_save_to_watch_later_kids_flyout_button", summary = true)
        )

        PreferenceScreen.SHORTS.addPreferences(
            SwitchPreference("morphe_save_to_watch_later_shorts_flyout_button", summary = true)
        )

        initializeTopControl(EXTENSION_BUTTON)
    }
}
