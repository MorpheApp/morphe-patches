@file:Suppress("SpellCheckingInspection")

package app.morphe.patches.youtube.interaction.playall

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.shared.misc.settings.preference.noTitleUnsortedPreferenceCategory
import app.morphe.patches.youtube.layout.buttons.overlay.addPlayerOverlayPreferences
import app.morphe.patches.youtube.layout.buttons.overlay.playerOverlayButtonsSettingsPatch
import app.morphe.patches.youtube.layout.player.icons.copyPlayerButtonIcons
import app.morphe.patches.youtube.layout.player.icons.playerIconStylePatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playercontrols.addTopControl
import app.morphe.patches.youtube.misc.playercontrols.initializeTopControl
import app.morphe.patches.youtube.misc.playercontrols.legacyPlayerControlsPatch
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patches.youtube.video.information.videoInformationPatch

private val playAllButtonResourcePatch = resourcePatch {
    dependsOn(
        settingsPatch,
        legacyPlayerControlsPatch,
        playerIconStylePatch
    )

    execute {

        copyPlayerButtonIcons("playallbutton", "morphe_play_all_button")
    }

    finalize {
        addTopControl("playallbutton",
            "@+id/morphe_play_all_button",
            "@+id/morphe_play_all_button")
    }
}

private const val EXTENSION_BUTTON = "Lapp/morphe/extension/youtube/videoplayer/PlayAllButton;"

@Suppress("unused")
val playAllButtonPatch = bytecodePatch(
    name = "Play all",
    description = "Adds an option to play all the videos from a channel and to display play all button in the video player.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        playAllButtonResourcePatch,
        playerOverlayButtonsSettingsPatch,
        videoInformationPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        addPlayerOverlayPreferences(
            noTitleUnsortedPreferenceCategory(
                SwitchPreference("morphe_play_all_button", summary = true),
                ListPreference("morphe_play_all_button_type")
            )
        )

        initializeTopControl(EXTENSION_BUTTON)
    }
}
