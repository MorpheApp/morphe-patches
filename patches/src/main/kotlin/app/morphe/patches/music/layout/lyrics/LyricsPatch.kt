/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2269
 * https://github.com/MorpheApp/morphe-patches/pull/3033
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.layout.lyrics

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.all.misc.resources.addResourcesPatch
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.litho.filter.lithoFilterPatch
import app.morphe.patches.music.misc.settings.PreferenceScreen
import app.morphe.patches.music.misc.settings.settingsPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.patches.music.shared.MediaSessionSetMetadataFingerprint
import app.morphe.patches.music.shared.hookMediaSessionArgument
import app.morphe.patches.music.video.information.musicVideoInformationPatch
import app.morphe.patches.shared.MediaSessionSetPlaybackStateFingerprint
import app.morphe.patches.shared.misc.litho.filter.addLithoFilter
import app.morphe.patches.shared.misc.settings.preference.InputType
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.patches.shared.misc.settings.preference.NonInteractivePreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceCategory
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference.Sorting
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.shared.misc.settings.preference.TextPreference
import app.morphe.util.ResourceGroup
import app.morphe.util.copyResources
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstructionOrThrow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import java.util.logging.Logger

private const val EXTENSION_CLASS = "Lapp/morphe/extension/music/patches/lyrics/LyricsPatch;"
private const val PANEL_INSTALLER_CLASS = "Lapp/morphe/extension/music/patches/lyrics/LyricsPanelInstaller;"
private const val LOCKSCREEN_CLASS = "Lapp/morphe/extension/music/patches/lyrics/LockScreenLyrics;"
private const val MINIPLAYER_LYRICS_CLASS = "Lapp/morphe/extension/music/patches/lyrics/MiniPlayerLyrics;"

private const val LYRICS_PANEL_FILTER =
    "Lapp/morphe/extension/music/patches/components/LyricsPanelFilter;"

@Suppress("unused")
val lyricsPatch = bytecodePatch(
    name = "Third-party lyrics",
    description = "Adds an option to show synced lyrics with experience enhancement from 15+ providers in the lyrics panel."
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        addResourcesPatch,
        lithoFilterPatch,
        musicVideoInformationPatch,
        // The copy button needs its icon whether the patch that owns
        // these resources is applied.
        resourcePatch {
            execute {
                copyResources(
                    "copyvideolinkbutton",
                    ResourceGroup("drawable", "morphe_yt_copy_bold.xml")
                )
            }
        }
    )

    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)

    execute {
        PreferenceScreen.LYRICS.addPreferences(
            SwitchPreference("morphe_music_lyrics_enabled", summary = true),
            PreferenceCategory(
                key = "morphe_music_lyrics_section_service",
                sorting = Sorting.UNSORTED,
                preferences = setOf(
                    NonInteractivePreference(
                        key = "morphe_music_lyrics_source",
                        titleKey = null,
                        summaryKey = "morphe_music_lyrics_source_summary",
                        tag = "app.morphe.extension.music.settings.preference.LyricsOrderedListPreference",
                        selectable = false,
                        dependency = "morphe_music_lyrics_enabled"
                    )
                )
            ),
            PreferenceCategory(
                key = "morphe_settings_music_lyrics_metadata",
                sorting = Sorting.UNSORTED,
                preferences = setOf(
                    TextPreference(
                        key = "morphe_music_lyrics_custom_regex",
                        inputType = InputType.TEXT_MULTI_LINE
                    ),
                    TextPreference(
                        key = "morphe_music_lyrics_text_filter",
                        inputType = InputType.TEXT_MULTI_LINE
                    ),
                    TextPreference(
                        key = "morphe_music_lyrics_credit_line_regex",
                        inputType = InputType.TEXT_MULTI_LINE
                    )
                )
            ),
            PreferenceCategory(
                key = "morphe_music_lyrics_section_overlay",
                sorting = Sorting.UNSORTED,
                preferences = setOf(
                    NonInteractivePreference(
                        key = "morphe_music_lyrics_text_size",
                        summaryKey = "morphe_music_lyrics_text_size_summary",
                        tag = "app.morphe.extension.shared.settings.preference.SeekBarPreference",
                        selectable = true,
                        dependency = "morphe_music_lyrics_enabled"
                    ),
                    SwitchPreference("morphe_music_lyrics_word_sync", summary = true),
                    SwitchPreference("morphe_music_lyrics_hide_played", summary = true),
                    SwitchPreference("morphe_music_lyrics_hide_unplayed", summary = true),
                    SwitchPreference("morphe_music_lyrics_tap_to_seek", summary = true),
                    SwitchPreference("morphe_music_lyrics_show_copy_button", summary = true),
                    SwitchPreference("morphe_music_lyrics_show_translate_button", summary = true),
                    ListPreference(
                        key = "morphe_music_lyrics_translation_language",
                        entriesKey = "morphe_language_entries",
                        entryValuesKey = "morphe_language_entry_values"
                    ),
                    SwitchPreference("morphe_music_lyrics_show_romanize_button", summary = true),
                    SwitchPreference(
                        key = "morphe_music_lyrics_use_ai_translation",
                        summary = true,
                        tag = "app.morphe.extension.music.settings.preference.LyricsAiConfigPreference"
                    ),
                    SwitchPreference("morphe_music_lyrics_show_refresh_button", summary = true),
                    SwitchPreference("morphe_music_lyrics_hide_info", summary = true),
                    SwitchPreference("morphe_music_lyrics_swap_trans_roma", summary = true)
                )
            ),
            PreferenceCategory(
                key = "morphe_music_lyrics_section_sync",
                sorting = Sorting.UNSORTED,
                preferences = setOf(
                    NonInteractivePreference(
                        key = "morphe_music_lyrics_offset_ms",
                        summaryKey = "morphe_music_lyrics_offset_ms_summary",
                        tag = "app.morphe.extension.shared.settings.preference.SeekBarPreference",
                        selectable = true,
                        dependency = "morphe_music_lyrics_enabled"
                    ),
                    SwitchPreference("morphe_music_lyrics_miniplayer"),
                    SwitchPreference("morphe_music_lyrics_mediasession"),
                    SwitchPreference("morphe_music_lyrics_display_artist_first", summary = true)
                )
            ),
            PreferenceCategory(
                key = "morphe_music_lyrics_section_about",
                sorting = Sorting.UNSORTED,
                preferences = setOf(
                    NonInteractivePreference(
                        key = "morphe_music_lyrics_about",
                        titleKey = null,
                        summaryKey = "morphe_music_lyrics_about_summary",
                        dependency = "morphe_music_lyrics_enabled"
                    )
                )
            )
        )

        // The panel content is built by Elements, so there is no view to hook. The timed
        // lyrics component is the earliest signal that the opened panel is the lyrics one.
        addLithoFilter(LYRICS_PANEL_FILTER)

        // Some accounts get the panel without a heading, and then nothing in the view tree
        // tells the lyrics panel from the other panels sharing the same container. The panel
        // the app itself put in the container is what tells them apart. Panels with a
        // heading do not need this, so a miss here leaves the rest of the patch working.
        val log = Logger.getLogger(this::class.java.name)
        runCatching {
            EngagementPanelControllerFingerprint.classDef.apply {
                val controllerType = type

                // Only instance fields the controller reassigns can hold the panel it shows,
                // and leaving the primitive ones out keeps flags from matching as a type.
                val panelFieldTypes = fields.filter { field ->
                    field.type.startsWith("L") &&
                        !AccessFlags.STATIC.isSet(field.accessFlags) &&
                        !AccessFlags.FINAL.isSet(field.accessFlags)
                }.mapTo(mutableSetOf()) { it.type }

                // The only method taking that type along with a flag is the one that assigns it.
                val setCurrentPanel = methods.single { method ->
                    method.returnType == "V" &&
                        method.parameterTypes.size == 2 &&
                        method.parameterTypes[1].toString() == "Z" &&
                        method.parameterTypes[0].toString() in panelFieldTypes
                }
                val panelType = setCurrentPanel.parameterTypes.first().toString()

                setCurrentPanel.apply {
                    // Hooked after the assignment, because the argument is cleared to null
                    // before it when the panel is being closed rather than shown. The panel
                    // is also handed to other objects here, so the owner is checked too.
                    val assignIndex = indexOfFirstInstructionOrThrow {
                        opcode == Opcode.IPUT_OBJECT &&
                            getReference<FieldReference>()?.let { field ->
                                field.type == panelType && field.definingClass == controllerType
                            } == true
                    }
                    addInstruction(
                        assignIndex + 1,
                        "invoke-static { p1 }, " +
                            "$PANEL_INSTALLER_CLASS->onEngagementPanelChanged(Ljava/lang/Object;)V"
                    )
                }
            }
        }.onFailure {
            log.warning(
                "Engagement panel controller not found, a lyrics panel shown without a " +
                    "heading will not be used: ${it.message}"
            )
        }

        MediaSessionSetMetadataFingerprint.hookMediaSessionArgument(
            "$EXTENSION_CLASS->onSetMetadata(Landroid/media/MediaMetadata;)V"
        )

        MediaSessionSetMetadataFingerprint.let {
            it.clearMatch()
            val method = it.method
            val index = it.instructionMatches.first().index
            val instruction = method.getInstruction<FiveRegisterInstruction>(index)
            val sessionRegister = instruction.registerC
            val metadataRegister = instruction.registerD
            method.addInstruction(
                index,
                "invoke-static { v$sessionRegister, v$metadataRegister }, " +
                    "$LOCKSCREEN_CLASS->onMediaSessionSetMetadata(Landroid/media/session/MediaSession;Landroid/media/MediaMetadata;)V"
            )
            method.addInstruction(
                index,
                "invoke-static { v$sessionRegister, v$metadataRegister }, " +
                    "$MINIPLAYER_LYRICS_CLASS->onMediaSessionSetMetadata(Landroid/media/session/MediaSession;Landroid/media/MediaMetadata;)V"
            )
        }

        MediaSessionSetPlaybackStateFingerprint.hookMediaSessionArgument(
            "$EXTENSION_CLASS->onSetPlaybackState(Landroid/media/session/PlaybackState;)V"
        )
    }
}
