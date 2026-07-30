/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.layout.hide.general

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.litho.filter.lithoFilterPatch
import app.morphe.patches.music.misc.playservice.is_8_51_or_greater
import app.morphe.patches.music.misc.settings.PreferenceScreen
import app.morphe.patches.music.misc.settings.settingsPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.patches.shared.misc.litho.filter.addLithoFilter
import app.morphe.patches.shared.misc.settings.preference.InputType
import app.morphe.patches.shared.misc.settings.preference.PreferenceCategory
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference.Sorting
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.shared.misc.settings.preference.TextPreference
import app.morphe.util.injectHideViewCall
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val COMMENTS_FILTER =
    "Lapp/morphe/extension/music/patches/components/CommentsFilter;"
private const val CUSTOM_FILTER =
    "Lapp/morphe/extension/music/patches/components/CustomFilter;"
private const val LAYOUT_COMPONENTS_FILTER =
    "Lapp/morphe/extension/music/patches/components/LayoutComponentsFilter;"

@Suppress("unused")
val hideLayoutComponentsPatch = bytecodePatch(
    name = "Hide layout components",
    description = "Adds options to hide general layout components."
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        lithoFilterPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)

    execute {
        PreferenceScreen.FEED.addPreferences(
            SwitchPreference("morphe_music_hide_explore_shelf"),
            SwitchPreference("morphe_music_hide_grid_shelves"),
            SwitchPreference("morphe_music_hide_horizontal_shelves"),
            SwitchPreference("morphe_music_hide_list_shelves"),
            SwitchPreference("morphe_music_hide_new_from_shelf"),
            SwitchPreference("morphe_music_hide_playlist_shelves"),
            SwitchPreference("morphe_music_hide_speed_dial_shelf"),
            SwitchPreference("morphe_music_hide_suggested_for_you_shelf")
        )

        PreferenceScreen.GENERAL.addPreferences(
            PreferenceScreenPreference(
                key = "morphe_music_custom_filter_screen",
                titleKey = "morphe_custom_filter_screen_title",
                summaryKey = "morphe_custom_filter_screen_summary",
                sorting = Sorting.UNSORTED,
                preferences = setOf(
                    SwitchPreference(
                        key = "morphe_music_custom_filter",
                        titleKey = "morphe_custom_filter_title"
                    ),
                    TextPreference(
                        key = "morphe_music_custom_filter_strings",
                        titleKey = "morphe_custom_filter_strings_title",
                        summaryKey = "morphe_custom_filter_strings_summary",
                        inputType = InputType.TEXT_MULTI_LINE
                    )
                )
            )
        )

        PreferenceScreen.PLAYER.addPreferences(
            PreferenceScreenPreference(
                key = "morphe_music_comments_screen",
                titleKey = "morphe_music_comments_screen_title",
                summaryKey = "morphe_music_comments_screen_summary",
                sorting = Sorting.UNSORTED,
                preferences = setOf(
                    SwitchPreference("morphe_music_hide_comments_community_guidelines"),
                    SwitchPreference("morphe_music_hide_comments_context"),
                    SwitchPreference("morphe_music_hide_comments_emoji_button"),
                    SwitchPreference("morphe_music_hide_comments_info_button"),
                    SwitchPreference("morphe_music_hide_comments_timestamp_button")
                )
            ),
            SwitchPreference("morphe_music_hide_audio_video_toggle"),
            PreferenceCategory(
                titleKey = "morphe_music_hide_lyrics_panel_category_title",
                preferences = setOf(
                    SwitchPreference("morphe_music_hide_lyrics_share_button"),
                    SwitchPreference("morphe_music_hide_lyrics_translate_button")
                )
            ),
            SwitchPreference("morphe_music_hide_repeat_button"),
            SwitchPreference("morphe_music_hide_shuffle_button"),
        )

        addLithoFilter(COMMENTS_FILTER)
        addLithoFilter(CUSTOM_FILTER)
        addLithoFilter(LAYOUT_COMPONENTS_FILTER)

        // region hide audio / video toggle
        AudioVideoSwitchPillContainerFingerprint.matchAll().forEach { match ->
            match.method.injectHideViewCall(
                match.instructionMatches.last().index,
                LAYOUT_COMPONENTS_FILTER,
                "hideAudioVideoToggle"
            )
        }
        // endregion

        // region hide comments info button
        InformationButtonFingerprint.let {
            it.method.apply {
                val checkCastIndex = it.instructionMatches[1].index
                val viewRegister = getInstruction<OneRegisterInstruction>(checkCastIndex).registerA

                addInstruction(
                    checkCastIndex + 1,
                    "invoke-static { v$viewRegister }, $COMMENTS_FILTER->hideCommentsInfoButton(Landroid/view/View;)V"
                )
            }
        }
        //endregion

        // region hide repeat button
        val repeatFingerprints = if (is_8_51_or_greater) {
            listOf(
                OverlayQueueLoopButtonFingerprint,
                PlaybackQueueLoopButtonFingerprint
            )
        } else {
            listOf(QueueLoopButtonFingerprint)
        }

        repeatFingerprints.forEach { fingerprint ->
            fingerprint.matchAllOrNull()?.forEach { match ->
                match.method.injectHideViewCall(
                    match.instructionMatches.last().index,
                    LAYOUT_COMPONENTS_FILTER,
                    "hideRepeatButton"
                )
            }
        }
        // endregion

        // region hide shuffle button
        val shuffleFingerprints = if (is_8_51_or_greater) {
            listOf(
                OverlayQueueShuffleButtonFingerprint,
                PlaybackQueueShuffleButtonFingerprint
            )
        } else {
            listOf(QueueShuffleButtonFingerprint)
        }

        shuffleFingerprints.forEach { fingerprint ->
            fingerprint.matchAllOrNull()?.forEach { match ->
                match.method.injectHideViewCall(
                    match.instructionMatches.last().index,
                    LAYOUT_COMPONENTS_FILTER,
                    "hideShuffleButton"
                )
            }
        }
        // endregion
    }
}
