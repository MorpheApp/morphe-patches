/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.video.voiceovertranslation

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.patches.shared.misc.settings.preference.NonInteractivePreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.shared.misc.settings.preference.TextPreference
import app.morphe.patches.youtube.layout.player.buttons.addPlayerBottomButton
import app.morphe.patches.youtube.layout.player.buttons.playerOverlayButtonsHookPatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playercontrols.addLegacyBottomControl
import app.morphe.patches.youtube.misc.playercontrols.initializeLegacyBottomControl
import app.morphe.patches.youtube.misc.playercontrols.legacyPlayerControlsPatch
import app.morphe.patches.youtube.misc.playertype.playerTypeHookPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patches.youtube.video.information.videoInformationPatch
import app.morphe.patches.youtube.video.information.videoTimeHook
import app.morphe.patches.youtube.video.videoid.hookVideoId
import app.morphe.util.ResourceGroup
import app.morphe.util.copyResources

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/voiceovertranslation/VoiceOverTranslationPatch;"

private const val EXTENSION_BUTTON =
    "Lapp/morphe/extension/youtube/videoplayer/VoiceOverTranslationButton;"

private val voiceOverTranslationResourcePatch = resourcePatch {
    dependsOn(legacyPlayerControlsPatch)

    execute {
        copyResources(
            "voiceovertranslationbutton",
            ResourceGroup(
                "drawable",
                "morphe_yt_vot.xml",
                "morphe_yt_vot_bold.xml",
            )
        )

        addLegacyBottomControl("voiceovertranslationbutton")
    }
}

@Suppress("unused")
val voiceOverTranslationPatch = bytecodePatch(
    name = "Voice over translation",
    description = "Adds additional voice over languages using text-to-speech synchronized to the video playback.",
) {
    dependsOn(
        sharedExtensionPatch,
        videoInformationPatch,
        playerTypeHookPatch,
        playerOverlayButtonsHookPatch,
        legacyPlayerControlsPatch,
        voiceOverTranslationResourcePatch,
        votOriginalVolumeBytecodePatch
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.VIDEO.addPreferences(
            PreferenceScreenPreference(
                key = "morphe_vot_screen",
                sorting = PreferenceScreenPreference.Sorting.UNSORTED,
                preferences = setOf(
                    SwitchPreference("morphe_vot_enabled"),
                    ListPreference("morphe_vot_caption_language"),
                    NonInteractivePreference("morphe_vot_max_speech_rate",
                        tag = "app.morphe.extension.shared.settings.preference.SeekBarPreference",
                        selectable = true),
                    ListPreference("morphe_vot_translation_service"),
                    NonInteractivePreference("morphe_vot_openrouter_info",
                        titleKey = "morphe_vot_service_openrouter",
                        tag = "app.morphe.extension.youtube.settings.preference.VoiceOverTranslationOpenRouterInfoPreference",
                        selectable = true),
                    TextPreference("morphe_vot_openrouter_api_key"),
                    TextPreference("morphe_vot_openrouter_model",
                        summaryKey = null,
                        tag = "app.morphe.extension.youtube.settings.preference.VoiceOverTranslationModelPreference"),
                    NonInteractivePreference("morphe_vot_mymemory_info",
                        titleKey = "morphe_vot_service_mymemory",
                        tag = "app.morphe.extension.youtube.settings.preference.VoiceOverTranslationMyMemoryInfoPreference",
                        selectable = true),
                    TextPreference("morphe_vot_mymemory_email")
                )
            )
        )

        hookVideoId("$EXTENSION_CLASS->newVideoLoaded(Ljava/lang/String;)V")
        videoTimeHook(EXTENSION_CLASS, "videoTimeChanged")

        addPlayerBottomButton(EXTENSION_BUTTON)
        initializeLegacyBottomControl(EXTENSION_BUTTON)
    }
}
