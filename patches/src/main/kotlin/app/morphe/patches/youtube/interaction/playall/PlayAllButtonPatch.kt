package app.morphe.patches.youtube.interaction.playall

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.patch
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.shared.misc.settings.preference.noTitleUnsortedPreferenceCategory
import app.morphe.patches.youtube.layout.buttons.overlay.addPlayerOverlayPreferences
import app.morphe.patches.youtube.layout.buttons.overlay.playerOverlayButtonsSettingsPatch
import app.morphe.patches.youtube.layout.player.icons.copyPlayerButtonIcons
import app.morphe.patches.youtube.layout.playlistautoplay.NavigationIntentEnumFingerprint
import app.morphe.patches.youtube.layout.playlistautoplay.NavigationRequestFingerprint
import app.morphe.patches.youtube.layout.playlistautoplay.playlistRepeatModeHookPatch
import app.morphe.patches.youtube.layout.playlistautoplay.queueNavigationItemFingerprint
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playercontrols.addTopControl
import app.morphe.patches.youtube.misc.playercontrols.initializeTopControl
import app.morphe.patches.youtube.misc.playercontrols.legacyPlayerControlsPatch
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patches.youtube.video.information.videoInformationPatch

private val playAllButtonResourcePatch = patch {
    dependsOn(
        settingsPatch,
        legacyPlayerControlsPatch
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

val playAllButtonPatch = patch(
    name = "Play all",
    description = "Adds an option to play all the videos from a channel and to display play all button in the video player.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        playAllButtonResourcePatch,
        playerOverlayButtonsSettingsPatch,
        playlistRepeatModeHookPatch,
        videoInformationPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        addPlayerOverlayPreferences(
            noTitleUnsortedPreferenceCategory(
                SwitchPreference("morphe_play_all_button", summary = true),
                ListPreference("morphe_play_all_button_type"),
                SwitchPreference("morphe_play_all_button_reverse", summary = true)
            )
        )

        initializeTopControl(EXTENSION_BUTTON)

        // Reverse order. The generated playlist is loaded into the playback queue, which picks the
        // next video for AUTOPLAY and the previous video for PREVIOUS, and repeats the playlist in both
        // directions. Swapping the request only here keeps the rest of the AUTOPLAY handling.
        val enumType = NavigationIntentEnumFingerprint.originalClassDef.type
        val requestConstructor = NavigationRequestFingerprint.originalMethod
        val requestType = requestConstructor.definingClass
        val requestParameters = requestConstructor.parameterTypes.joinToString("")

        queueNavigationItemFingerprint(requestType, enumType).let {
            val enumField = it.instructionMatches.first().fieldReference

            it.method.addInstructionsWithLabels(
                0,
                """
                    move-object/from16 v0, p1
                    iget-object v1, v0, $enumField
                    invoke-static { v1 }, $EXTENSION_BUTTON->getNavigationIntent(Ljava/lang/Enum;)Ljava/lang/Enum;
                    move-result-object v2
                    if-eq v1, v2, :original
                    check-cast v2, $enumType
                    new-instance v0, $requestType
                    # The queue reads only the intent and video of the request, and PREVIOUS has no video.
                    const/4 v1, 0x0
                    invoke-direct { v0, v2, v1, v1 }, $requestType-><init>($requestParameters)V
                    move-object/from16 p1, v0
                    :original
                    nop
                """
            )
        }
    }
}
