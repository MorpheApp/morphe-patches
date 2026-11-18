/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4451
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.interaction.soundproof

import app.morphe.patcher.patch.patch
import app.morphe.patcher.patch.resourceXml
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.mediasession.addSetMetadataWithSessionHook
import app.morphe.patches.music.misc.mediasession.mediaSessionHookPatch
import app.morphe.patches.music.misc.playservice.is_9_39_or_greater
import app.morphe.patches.music.misc.playservice.versionCheckPatch
import app.morphe.patches.music.misc.settings.PreferenceScreen
import app.morphe.patches.music.misc.settings.settingsPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.patches.shared.misc.settings.preference.NonInteractivePreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceCategory
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.shared.misc.settings.preference.screenInfoPreferenceCategory
import app.morphe.util.ResourceGroup
import app.morphe.util.copyResources

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/music/patches/soundproof/SoundProofPatch;"

private const val SEEK_BAR_PREFERENCE =
    "app.morphe.extension.shared.settings.preference.SeekBarPreference"

private const val ABOUT_PREFERENCE =
    "app.morphe.extension.music.patches.soundproof.ui.SoundProofAboutPreference"

private const val BUTTON_VIEW_TAG =
    "app.morphe.extension.music.patches.soundproof.ui.SoundProofButton"

private val soundProofButtonResourcePatch = patch(
    description = "Adds the SoundProof button to the top bar of the player."
) {
    dependsOn(versionCheckPatch)

    execute {
        copyResources(
            "soundproof",
            ResourceGroup(
                "drawable",
                "morphe_soundproof_shield.xml",
                "morphe_soundproof_shield_alert.xml",
                "morphe_soundproof_shield_check.xml",
                "morphe_soundproof_shield_question.xml"
            )
        )

        val playerLayouts = mutableListOf("res/layout/watch_while_layout.xml")
        if (is_9_39_or_greater) {
            // Inflated instead of the layout above when a server flag defers the player.
            playerLayouts += "res/layout/watch_while_layout_player.xml"
        }

        playerLayouts.forEach { playerLayout ->
            resourceXml(playerLayout).use { layout ->
                // Top bar: collapse, spacer, centered audio and video switch, cast, overflow menu.
                val castButton = layout.elementByAttribute("android:id", "@id/cast_button_container")
                val topBar = castButton.parent!!

                // The switch gets the width left over by the other views, and the spacer is as wide
                // as a button only to keep the switch centered. The button replaces the spacer when
                // it is shown, so the switch keeps its width and stays centered.
                val spacer = topBar.children("Space").first()
                spacer.setAttribute("android:id", "@+id/morphe_soundproof_spacer")

                topBar.insertChild(spacer.index, BUTTON_VIEW_TAG) {
                    setAttribute("android:id", "@+id/morphe_soundproof_button")
                    setAttribute("android:layout_width", "@dimen/remix_generic_button_size")
                    setAttribute("android:layout_height", "@dimen/remix_generic_button_size")
                    setAttribute("android:scaleType", "center")
                    setAttribute("style", "@style/MusicPlayerButton")
                }
            }
        }
    }
}

val soundProofPatch = patch(
    name = "SoundProof",
    description = "Adds an option to skip tracks of artists reported as AI in the SoundProof community database."
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        mediaSessionHookPatch,
        soundProofButtonResourcePatch
    )

    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)

    execute {
        PreferenceScreen.PLAYER.addPreferences(
            PreferenceScreenPreference(
                key = "morphe_music_soundproof_screen",
                sorting = PreferenceScreenPreference.Sorting.UNSORTED,
                preferences = setOf(
                    SwitchPreference("morphe_music_soundproof_show_button", summary = true),
                    SwitchPreference("morphe_music_soundproof_skip_ai_artists", summary = true),
                    SwitchPreference("morphe_music_soundproof_toast_on_skip", summary = true),
                    PreferenceCategory(
                        key = "morphe_music_soundproof_threshold",
                        sorting = PreferenceScreenPreference.Sorting.UNSORTED,
                        preferences = setOf(
                            NonInteractivePreference(
                                key = "morphe_music_soundproof_minimum_ai_percent",
                                tag = SEEK_BAR_PREFERENCE,
                                selectable = true
                            ),
                            NonInteractivePreference(
                                key = "morphe_music_soundproof_minimum_votes",
                                tag = SEEK_BAR_PREFERENCE,
                                selectable = true
                            )
                        )
                    ),
                    screenInfoPreferenceCategory(
                        key = "morphe_music_soundproof_about_category",
                        preferences = setOf(
                            NonInteractivePreference(
                                key = "morphe_music_soundproof_about",
                                titleKey = null,
                                summaryKey = "morphe_music_soundproof_about_summary",
                                tag = ABOUT_PREFERENCE,
                                selectable = true
                            )
                        )
                    )
                )
            )
        )

        // Both the session and the metadata are needed: the artist is read from the metadata,
        // and the skip goes through the session's own transport controls.
        addSetMetadataWithSessionHook(
            "$EXTENSION_CLASS->onSetMetadata(Landroid/media/session/MediaSession;Landroid/media/MediaMetadata;)V"
        )
    }
}
