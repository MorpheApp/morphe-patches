/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.patches.music.interaction.scrobbling

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.settings.PreferenceScreen
import app.morphe.patches.music.misc.settings.settingsPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.patches.shared.misc.settings.preference.NonInteractivePreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceCategory
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference.Sorting
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.shared.misc.settings.preference.TextPreference
import app.morphe.patches.youtube.layout.returnyoutubedislike.DislikeFingerprint
import app.morphe.patches.youtube.layout.returnyoutubedislike.EndpointServiceNameFingerprint
import app.morphe.patches.youtube.layout.returnyoutubedislike.likeEndpointParserFingerprint
import app.morphe.patches.youtube.layout.returnyoutubedislike.requestParameterCheckFingerprint
import app.morphe.util.ResourceGroup
import app.morphe.util.copyResources
import app.morphe.util.getFreeRegisterProvider
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

private const val EXTENSION_CLASS = "Lapp/morphe/extension/music/patches/scrobbling/ScrobblePatch;"

@Suppress("unused")
val scrobblingPatch = bytecodePatch(
    name = "Scrobbling",
    description = "Adds options to add played tracks to Last.fm and ListenBrainz.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch
    )

    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)

    execute {
        PreferenceScreen.SCROBBLING.addPreferences(
            PreferenceCategory(
                key = "morphe_music_listenbrainz",
                preferences = setOf(
                    NonInteractivePreference(
                        key = "morphe_music_listenbrainz_token_ui",
                        titleKey = "morphe_music_listenbrainz_token_title",
                        summaryKey = null,
                        tag = "app.morphe.extension.music.settings.preference.ListenBrainzTokenPreference",
                        selectable = true
                    ),
                    SwitchPreference("morphe_music_listenbrainz_enabled"),
                    SwitchPreference("morphe_music_listenbrainz_now_playing"),
                    NonInteractivePreference(
                        key = "morphe_music_listenbrainz_min_song_duration",
                        summaryKey = "morphe_music_listenbrainz_min_song_duration_summary",
                        tag = "app.morphe.extension.shared.settings.preference.SeekBarPreference",
                        selectable = true
                    ),
                    NonInteractivePreference(
                        key = "morphe_music_listenbrainz_delay_percent",
                        summaryKey = "morphe_music_listenbrainz_delay_percent_summary",
                        tag = "app.morphe.extension.shared.settings.preference.SeekBarPreference",
                        selectable = true
                    ),
                    NonInteractivePreference(
                        key = "morphe_music_listenbrainz_delay_seconds",
                        summaryKey = "morphe_music_listenbrainz_delay_seconds_summary",
                        tag = "app.morphe.extension.shared.settings.preference.SeekBarPreference",
                        selectable = true
                    )
                )
            ),
            PreferenceCategory(
                key = "morphe_music_lastfm",
                preferences = setOf(
                    NonInteractivePreference(
                        key = "morphe_music_lastfm_token_ui",
                        titleKey = "morphe_music_lastfm_token_title",
                        summaryKey = null,
                        tag = "app.morphe.extension.music.settings.preference.LastFMTokenPreference",
                        selectable = true
                    ),
                    SwitchPreference("morphe_music_lastfm_enabled"),
                    SwitchPreference("morphe_music_lastfm_now_playing"),
                    SwitchPreference("morphe_music_lastfm_love_on_like", summary = true),
                    NonInteractivePreference(
                        key = "morphe_music_lastfm_min_song_duration",
                        summaryKey = "morphe_music_lastfm_min_song_duration_summary",
                        tag = "app.morphe.extension.shared.settings.preference.SeekBarPreference",
                        selectable = true
                    ),
                    NonInteractivePreference(
                        key = "morphe_music_lastfm_delay_percent",
                        summaryKey = "morphe_music_lastfm_delay_percent_summary",
                        tag = "app.morphe.extension.shared.settings.preference.SeekBarPreference",
                        selectable = true
                    ),
                    NonInteractivePreference(
                        key = "morphe_music_lastfm_delay_seconds",
                        summaryKey = "morphe_music_lastfm_delay_seconds_summary",
                        tag = "app.morphe.extension.shared.settings.preference.SeekBarPreference",
                        selectable = true
                    )
                )
            ),
            PreferenceCategory(
                key = "morphe_settings_music_scrobbling_metadata",
                preferences = setOf(
                    SwitchPreference("morphe_music_scrobbling_metadata_cleanup"),
                    TextPreference("morphe_music_scrobbling_custom_regex")
                )
            ),
            NonInteractivePreference(
                key = "morphe_music_scrobbling_about",
                titleKey = "morphe_music_scrobbling_about_title",
                summaryKey = "morphe_music_scrobbling_about_summary"
            )
        )

        MediaSessionSetPlaybackStateFingerprint.let {
            it.method.apply {
                val index = it.instructionMatches.first().index
                val register = getInstruction<FiveRegisterInstruction>(index).registerD
                addInstruction(
                    index,
                    "invoke-static { v$register }, $EXTENSION_CLASS->" +
                            "onSetPlaybackState(Landroid/media/session/PlaybackState;)V"
                )
            }
        }

        MediaSessionSetMetadataFingerprint.let {
            it.method.apply {
                val index = it.instructionMatches.first().index
                val register = getInstruction<FiveRegisterInstruction>(index).registerD
                addInstruction(
                    index,
                    "invoke-static { v$register }, $EXTENSION_CLASS->" +
                            "onSetMetadata(Landroid/media/MediaMetadata;)V"
                )
            }
        }

        // Hook like/dislike/remove like button clicks
        val endPointServiceNameField = EndpointServiceNameFingerprint
            .instructionMatches.last().instruction.getReference<FieldReference>()!!
        val likeEndpointParserClass = DislikeFingerprint.classDef.superclass!!
        val videoIdField = requestParameterCheckFingerprint(likeEndpointParserClass)
            .instructionMatches.last().instruction.getReference<FieldReference>()!!

        likeEndpointParserFingerprint(likeEndpointParserClass).let {
            it.method.apply {
                val matchIndex = it.instructionMatches[1].index
                val insertIndex = matchIndex + 1
                val likeEndpointTargetClassRegister =
                    getInstruction<TwoRegisterInstruction>(matchIndex).registerA
                val registerProvider = getFreeRegisterProvider(
                    insertIndex, 2,
                    likeEndpointTargetClassRegister
                )
                val endPointServiceNameRegister = registerProvider.getFreeRegister()
                val videoIdRegister = registerProvider.getFreeRegister()

                addInstructions(
                    insertIndex,
                    """
                        iget-object v$endPointServiceNameRegister, p0, $endPointServiceNameField
                        iget-object v$videoIdRegister, v$likeEndpointTargetClassRegister, $videoIdField
                        invoke-static { v$endPointServiceNameRegister, v$videoIdRegister }, $EXTENSION_CLASS->onLikeClicked(Ljava/lang/String;Ljava/lang/String;)V
                    """
                )
            }
        }
    }
}
