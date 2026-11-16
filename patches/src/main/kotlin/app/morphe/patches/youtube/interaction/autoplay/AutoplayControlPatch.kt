/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4659
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.interaction.autoplay

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.InputType
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.shared.misc.settings.preference.TextPreference
import app.morphe.patches.shared.misc.settings.preference.noTitleUnsortedPreferenceCategory
import app.morphe.patches.youtube.layout.player.buttons.addPlayerBottomButton
import app.morphe.patches.youtube.layout.player.buttons.playerOverlayButtonsHookPatch
import app.morphe.patches.youtube.layout.playlistautoplay.disablePlaylistAutoplayPatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patches.youtube.video.information.videoInformationPatch
import app.morphe.patches.youtube.video.videoid.hookBackgroundPlayVideoId
import app.morphe.patches.youtube.video.videoid.videoIdPatch

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/AutoplayControlPatch;"

private const val EXTENSION_BUTTON =
    "Lapp/morphe/extension/youtube/videoplayer/AutoplayButton;"

@Suppress("unused")
val autoplayControlPatch = bytecodePatch(
    name = "Autoplay control",
    description = "Adds a player button and a rule for the next video: playlists and queues go on, " +
        "a Mix stops after a number of videos, a suggested video never plays.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        // Its navigation intent check calls the rule.
        disablePlaylistAutoplayPatch,
        playerOverlayButtonsHookPatch,
        videoInformationPatch,
        videoIdPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.PLAYER.addPreferences(
            noTitleUnsortedPreferenceCategory(
                SwitchPreference("morphe_autoplay_control", summary = true),
                ListPreference("morphe_autoplay_mode"),
                TextPreference("morphe_autoplay_mix_stop_after", inputType = InputType.NUMBER),
            )
        )

        addPlayerBottomButton(EXTENSION_BUTTON)

        // Mixes are mostly listened to in the background, where the regular video ID hook is not called.
        hookBackgroundPlayVideoId("$EXTENSION_CLASS->newVideoLoaded(Ljava/lang/String;)V")
    }
}
