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
import app.morphe.patches.youtube.misc.auth.authHookPatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playercontrols.addTopControl
import app.morphe.patches.youtube.misc.playercontrols.initializeTopControl
import app.morphe.patches.youtube.misc.playercontrols.injectVisibilityCheckCall
import app.morphe.patches.youtube.misc.playercontrols.legacyPlayerControlsPatch
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patches.youtube.video.information.videoInformationPatch
import app.morphe.util.ResourceGroup
import app.morphe.util.copyResources

private val saveToWatchLaterButtonResourcePatch = resourcePatch {
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

private const val EXTENSION_BUTTON =
    "Lapp/morphe/extension/youtube/videoplayer/SaveToWatchLaterButton;"

@Suppress("unused")
val saveToWatchLaterButtonPatch = bytecodePatch(
    name = "Save to watch later",
    description = "Adds an option to display save to watch later button in the video player.",
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

        initializeTopControl(EXTENSION_BUTTON)
        injectVisibilityCheckCall(EXTENSION_BUTTON)
    }
}
