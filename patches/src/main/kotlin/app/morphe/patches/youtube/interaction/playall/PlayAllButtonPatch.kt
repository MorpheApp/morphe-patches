@file:Suppress("SpellCheckingInspection")

package app.morphe.patches.youtube.interaction.playall

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceCategory
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference.Sorting
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.layout.player.buttons.addPlayerBottomButton
import app.morphe.patches.youtube.layout.player.buttons.playerOverlayButtonsHookPatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patches.youtube.video.information.videoInformationPatch
import app.morphe.util.ResourceGroup
import app.morphe.util.copyResources

private val playAllButtonResourcePatch = resourcePatch {
    dependsOn(settingsPatch)

    execute {
        PreferenceScreen.PLAYER.addPreferences(
            PreferenceCategory(
                titleKey = null,
                sorting = Sorting.UNSORTED,
                tag = "app.morphe.extension.shared.settings.preference.NoTitlePreferenceCategory",
                preferences = setOf(
                    SwitchPreference("morphe_play_all_button"),
                    ListPreference("morphe_play_all_button_type")
                )
            )
        )

        copyResources(
            "playallbutton",
            ResourceGroup(
                "drawable",
                "morphe_play_all_button.xml"
            )
        )
    }
}

private const val PLAY_ALL_BUTTON_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/youtube/videoplayer/PlayAllButton;"

@Suppress("unused")
val playAllButtonPatch = bytecodePatch(
    name = "Play all",
    description = "Adds an option to play all the videos from a channel and to display play all button in the video player.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        playAllButtonResourcePatch,
        playerOverlayButtonsHookPatch,
        videoInformationPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        addPlayerBottomButton(PLAY_ALL_BUTTON_CLASS_DESCRIPTOR)
    }
}