/*
 * Copyright (C) 2026 anddea
 *
 * This file is part of the revanced-patches project:
 * https://github.com/anddea/revanced-patches
 *
 * Original author(s):
 * - Jav1x (https://github.com/Jav1x)
 *
 * Ported to morphe-patches: https://github.com/MorpheApp/morphe-patches
 * Modified by: Jav1x (https://github.com/Jav1x)
 *
 * Licensed under the GNU General Public License v3.0.
 *
 * ------------------------------------------------------------------------
 * GPLv3 Section 7 – Attribution Notice
 * ------------------------------------------------------------------------
 *
 * This file contains substantial original work by the author(s) listed above.
 *
 * In accordance with Section 7 of the GNU General Public License v3.0,
 * the following additional terms apply to this file:
 *
 * 1. Attribution (Section 7(b)): This specific copyright notice and the
 *    list of original authors above must be preserved in any copy or
 *    derivative work. You may add your own copyright notice below it,
 *    but you may not remove the original one.
 *
 * 2. Origin (Section 7(c)): Modified versions must be clearly marked as
 *    such (e.g., by adding a "Modified by" line or a new copyright notice).
 *    They must not be misrepresented as the original work.
 *
 * ------------------------------------------------------------------------
 * Version Control Acknowledgement (Non-binding Request)
 * ------------------------------------------------------------------------
 *
 * While not a legal requirement of the GPLv3, the original author(s)
 * respectfully request that ports or substantial modifications retain
 * historical authorship credit in version control systems (e.g., Git),
 * listing original author(s) appropriately and modifiers as committers
 * or co-authors.
 */

package app.morphe.patches.youtube.video.voiceovertranslation

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.shared.misc.settings.preference.InputType
import app.morphe.patches.shared.misc.settings.preference.PreferenceCategory
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference.Sorting
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.shared.misc.settings.preference.TextPreference
import app.morphe.patches.youtube.layout.player.buttons.addPlayerBottomButton
import app.morphe.patches.youtube.layout.player.buttons.playerOverlayButtonsHookPatch
import app.morphe.patches.youtube.misc.playercontrols.addLegacyBottomControl
import app.morphe.patches.youtube.misc.playercontrols.initializeLegacyBottomControl
import app.morphe.patches.youtube.misc.playercontrols.injectVisibilityCheckCall
import app.morphe.patches.youtube.misc.playercontrols.legacyPlayerControlsPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patches.youtube.video.information.onCreateHook
import app.morphe.patches.youtube.video.information.videoInformationPatch
import app.morphe.patches.youtube.video.information.videoTimeHook
import app.morphe.patches.youtube.video.videoid.hookVideoId
import app.morphe.patches.youtube.video.videoid.videoIdPatch
import app.morphe.util.ResourceGroup
import app.morphe.util.copyResources

private const val EXTENSION_VOT_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/youtube/patches/voiceovertranslation/VoiceOverTranslationPatch;"

private const val EXTENSION_VOT_BUTTON =
    "Lapp/morphe/extension/youtube/videoplayer/VoiceOverTranslationButton;"

@Suppress("unused")
val voiceOverTranslationBytecodePatch = bytecodePatch(
    description = "voiceOverTranslationBytecodePatch"
) {
    dependsOn(
        videoInformationPatch,
        videoIdPatch,
        playerOverlayButtonsHookPatch,
        legacyPlayerControlsPatch,
    )

    execute {
        videoTimeHook(EXTENSION_VOT_CLASS_DESCRIPTOR, "setVideoTime")
        onCreateHook(EXTENSION_VOT_CLASS_DESCRIPTOR, "initialize")
        hookVideoId("$EXTENSION_VOT_CLASS_DESCRIPTOR->onVideoIdChanged(Ljava/lang/String;)V")
        addPlayerBottomButton(EXTENSION_VOT_BUTTON)
        initializeLegacyBottomControl(EXTENSION_VOT_BUTTON)
        injectVisibilityCheckCall(EXTENSION_VOT_BUTTON)
    }
}

private val voiceOverTranslationResourcePatch = resourcePatch {
    dependsOn(settingsPatch, legacyPlayerControlsPatch)

    execute {
        copyResources("voiceovertranslationbutton",
            ResourceGroup(resourceDirectoryName = "drawable",
                "morphe_yt_vot.xml", "morphe_yt_vot_activated.xml"))
        addLegacyBottomControl("voiceovertranslationbutton")

        PreferenceScreen.VOICE_OVER_TRANSLATION.addPreferences(
            PreferenceCategory(
                key = "morphe_vot_general_category",
                titleKey = null,
                tag = "app.morphe.extension.shared.settings.preference.NoTitlePreferenceCategory",
                sorting = Sorting.UNSORTED,
                preferences = setOf(
                    SwitchPreference("morphe_vot_enabled"),
                    ListPreference(
                        key = "morphe_vot_source_language",
                        entriesKey = "morphe_vot_source_language_entries",
                        entryValuesKey = "morphe_vot_source_language_entry_values",
                    ),
                    ListPreference(
                        key = "morphe_vot_target_language",
                        entriesKey = "morphe_vot_target_language_entries",
                        entryValuesKey = "morphe_vot_target_language_entry_values",
                    ),
                    SwitchPreference("morphe_vot_use_live_voices"),
                    TextPreference(
                        key = "morphe_vot_oauth_token",
                        inputType = InputType.TEXT,
                    ),
                )
            ),
            PreferenceCategory(
                key = "morphe_vot_proxy_category",
                titleKey = null,
                tag = "app.morphe.extension.shared.settings.preference.NoTitlePreferenceCategory",
                sorting = Sorting.UNSORTED,
                preferences = setOf(
                    SwitchPreference(
                        key = "morphe_vot_audio_proxy_enabled",
                        titleKey = "morphe_vot_audio_proxy_title",
                        summary = true,
                    ),
                    TextPreference(
                        key = "morphe_vot_proxy_url",
                        inputType = InputType.TEXT,
                    ),
                )
            )
        )
    }
}

@Suppress("unused")
val voiceOverTranslationPatch = bytecodePatch(
    name = "Voice Over Translation",
    description = "Adds an option to enable Yandex voice-over translation of video audio tracks.",
) {
    compatibleWith(COMPATIBILITY_YOUTUBE)
    dependsOn(voiceOverTranslationResourcePatch, voiceOverTranslationBytecodePatch,
        votOriginalVolumeBytecodePatch, settingsPatch)
    execute { }
}
