/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.interaction.savetowatchlater

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.shared.misc.settings.preference.TextPreference
import app.morphe.patches.shared.misc.settings.preference.noTitleUnsortedPreferenceCategory
import app.morphe.patches.youtube.layout.buttons.overlay.addPlayerOverlayPreferences
import app.morphe.patches.youtube.layout.buttons.overlay.playerOverlayButtonsSettingsPatch
import app.morphe.patches.youtube.layout.flyout.flyoutPatch
import app.morphe.patches.youtube.layout.player.icons.copyPlayerButtonIcons
import app.morphe.patches.youtube.layout.player.icons.copyPlayerIconStyles
import app.morphe.patches.youtube.misc.auth.authHookPatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.loadvideo.loadVideoHookPatch
import app.morphe.patches.youtube.misc.playercontrols.addTopControl
import app.morphe.patches.youtube.misc.playercontrols.initializeTopControl
import app.morphe.patches.youtube.misc.playercontrols.legacyPlayerControlsPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patches.youtube.video.information.videoInformationPatch
import app.morphe.util.setExtensionIsPatchIncluded

private val saveToWatchLaterButtonResourcePatch = resourcePatch {
    execute {
        copyPlayerButtonIcons("savetowatchlaterbutton", "morphe_save_to_watch_later_button")
        copyPlayerIconStyles("savetowatchlaterbutton", "morphe_add_to_queue_button")
    }
}

private const val EXTENSION_BUTTON =
    "Lapp/morphe/extension/youtube/videoplayer/SaveToWatchLaterButton;"
private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/SaveToWatchLaterPatch;"

@Suppress("unused")
val saveToWatchLaterButtonPatch = bytecodePatch(
    name = "Save to Watch later",
    description = "Adds an option to display save to Watch later button in the video player, " +
        "and adds a setting to redirect Watch later saves to a playlist of your choice.",
) {
    dependsOn(
        saveToWatchLaterButtonResourcePatch,
        settingsPatch,
        legacyPlayerControlsPatch,
        playerOverlayButtonsSettingsPatch,
        sharedExtensionPatch,
        videoInformationPatch,
        authHookPatch,
        flyoutPatch,
        loadVideoHookPatch,
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
            SwitchPreference("morphe_save_to_watch_later_kids_flyout_button", summary = true),
            TextPreference("morphe_save_to_watch_later_redirect_playlist_id")
        )

        PreferenceScreen.SHORTS.addPreferences(
            SwitchPreference("morphe_save_to_watch_later_shorts_flyout_button", summary = true)
        )

        initializeTopControl(EXTENSION_BUTTON)

        setExtensionIsPatchIncluded(EXTENSION_CLASS)

        // Rewrite the playlist id of any edit request that targets the stock Watch later
        // playlist, using the user configured redirect playlist. All 'Save to Watch later'
        // entry points that use the stock request path go through this single builder.
        val buildMethod = EditPlaylistBuildMethodFingerprint.method
        val playlistIdField = EditPlaylistBuildMethodFingerprint
            .instructionMatches.first().getFieldAccessed()

        buildMethod.addInstructions(
            0,
            """
                iget-object v0, p0, $playlistIdField
                invoke-static {v0}, $EXTENSION_CLASS->getRedirectPlaylistId(Ljava/lang/String;)Ljava/lang/String;
                move-result-object v0
                iput-object v0, p0, $playlistIdField
            """,
        )
    }
}
