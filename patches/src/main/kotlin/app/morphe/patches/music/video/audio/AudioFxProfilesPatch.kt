/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.video.audio

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.patch
import app.morphe.patcher.string
import app.morphe.patches.music.interaction.crossfade.AudioEffectSessionFingerprint
import app.morphe.patches.music.layout.lyrics.TabbedViewTabSelectedFingerprint
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.settings.PreferenceScreen
import app.morphe.patches.music.misc.settings.settingsPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.patches.music.video.speed.playbackSpeedPatch
import app.morphe.patches.shared.misc.audio.silence.ApplySkipSilenceFingerprint
import app.morphe.patches.shared.misc.audio.silence.SilenceSkippingProcessorQueueInputFingerprint
import app.morphe.patches.shared.misc.audio.silence.setSkipSilenceEnabledFingerprint
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.patches.shared.misc.settings.preference.NonInteractivePreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceCategory
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference.Sorting
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/music/patches/AudioFxProfilesPatch;"

private const val SEEK_BAR_TAG =
    "app.morphe.extension.shared.settings.preference.SeekBarPreference"

@Suppress("unused")
val audioFxProfilesPatch = patch(
    name = "Audio FX profiles",
    description = "Adds customizable real-time DSP audio profiles (Nightcore, Slowed + Reverb, Muffled, Lo-Fi, Daycore, Vaporwave, Live Concert, 8D Audio, Bass Boosted) with an FX button right next to Lyrics in the full-screen player.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        playbackSpeedPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)

    execute {
        PreferenceScreen.PLAYER.addPreferences(
            PreferenceScreenPreference(
                key = "morphe_music_audio_fx_screen",
                sorting = Sorting.UNSORTED,
                preferences = setOf(
                    SwitchPreference(
                        key = "morphe_music_audio_fx_enabled",
                        summary = true,
                    ),
                    SwitchPreference(
                        key = "morphe_music_audio_fx_show_player_tab",
                        summary = true,
                    ),
                    ListPreference("morphe_music_audio_fx_profile"),
                    PreferenceCategory(
                        key = "morphe_music_audio_fx_customization_category",
                        sorting = Sorting.UNSORTED,
                        preferences = setOf(
                            NonInteractivePreference(
                                key = "morphe_music_audio_fx_custom_speed",
                                tag = SEEK_BAR_TAG,
                                selectable = true,
                            ),
                            NonInteractivePreference(
                                key = "morphe_music_audio_fx_custom_pitch",
                                tag = SEEK_BAR_TAG,
                                selectable = true,
                            ),
                            NonInteractivePreference(
                                key = "morphe_music_audio_fx_bass_level",
                                tag = SEEK_BAR_TAG,
                                selectable = true,
                            ),
                            NonInteractivePreference(
                                key = "morphe_music_audio_fx_reverb_level",
                                tag = SEEK_BAR_TAG,
                                selectable = true,
                            ),
                            NonInteractivePreference(
                                key = "morphe_music_audio_fx_8d_speed",
                                tag = SEEK_BAR_TAG,
                                selectable = true,
                            ),
                            NonInteractivePreference(
                                key = "morphe_music_audio_fx_8d_depth",
                                tag = SEEK_BAR_TAG,
                                selectable = true,
                            ),
                        )
                    )
                )
            )
        )

        // Hook both TabbedView constructor and tab selection so the FX button is placed
        // directly next to the LYRICS tab button in the horizontal tab strip.
        TabbedViewTabSelectedFingerprint.classDef.methods.filter { method ->
            AccessFlags.CONSTRUCTOR.isSet(method.accessFlags)
        }.forEach { ctor ->
            val returnIndex = ctor.implementation!!.instructions.size - 1
            ctor.addInstruction(
                returnIndex,
                "invoke-static { p0 }, $EXTENSION_CLASS->onTabSelected(Landroid/view/ViewGroup;)V"
            )
        }

        TabbedViewTabSelectedFingerprint.method.addInstruction(
            0,
            "invoke-static { p0 }, $EXTENSION_CLASS->onTabSelected(Landroid/view/ViewGroup;)V"
        )

        // Hook ExoPlayer PCM ByteBuffer processor for real-time 8D binaural stereo rotation.
        runCatching {
            val skipSilenceField = ApplySkipSilenceFingerprint.instructionMatches[1].fieldReference
            ApplySkipSilenceFingerprint.apply {
                val match = instructionMatches[1]
                val booleanRegister = match.getInstruction<TwoRegisterInstruction>().registerA
                method.addInstructions(
                    match.index + 1,
                    """
                        invoke-static { v$booleanRegister }, $EXTENSION_CLASS->shouldKeepAudioProcessorActive(Z)Z
                        move-result v$booleanRegister
                    """
                )
            }
            setSkipSilenceEnabledFingerprint(skipSilenceField).method.addInstructions(
                0,
                """
                    invoke-static { p1 }, $EXTENSION_CLASS->shouldKeepAudioProcessorActive(Z)Z
                    move-result p1
                """
            )
            SilenceSkippingProcessorQueueInputFingerprint.method.addInstruction(
                0,
                "invoke-static { p1 }, $EXTENSION_CLASS->processAudioBuffer(Ljava/nio/ByteBuffer;)V"
            )
        }

        // Hook all audio effect session methods (open/update session ID) in YouTube Music's
        // AudioEffectSession manager class.
        AudioEffectSessionFingerprint.classDef.methods.filter { method ->
            method.returnType == "V" &&
                method.parameterTypes.size == 1 &&
                method.parameterTypes.first().toString() == "I"
        }.forEach { method ->
            method.addInstruction(
                0,
                "invoke-static { p1 }, $EXTENSION_CLASS->onAudioSessionId(I)V"
            )
        }

        // Also fallback-hook AudioTrack.getAudioSessionId() inside AudioTrackAudioOutput if present.
        runCatching {
            Fingerprint(
                classFingerprint = Fingerprint(
                    filters = listOf(string("AudioTrackAudioOutput"))
                ),
                filters = listOf(
                    methodCall(smali = "Landroid/media/AudioTrack;->getAudioSessionId()I")
                )
            ).let { fp ->
                val matchIndex = fp.instructionMatches.first().index
                val moveResultInst = fp.method.implementation!!.instructions.elementAt(matchIndex + 1)
                if (moveResultInst is OneRegisterInstruction) {
                    val reg = moveResultInst.registerA
                    fp.method.addInstruction(
                        matchIndex + 2,
                        "invoke-static { v$reg }, $EXTENSION_CLASS->onAudioSessionId(I)V"
                    )
                }
            }
        }
    }
}
