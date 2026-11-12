/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.video.audio

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
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
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.patches.shared.misc.settings.preference.NonInteractivePreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceCategory
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference.Sorting
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/music/patches/AudioFxProfilesPatch;"

private const val SEEK_BAR_TAG =
    "app.morphe.extension.shared.settings.preference.SeekBarPreference"

@Suppress("unused")
val audioFxProfilesPatch = patch(
    name = "Audio FX profiles",
    description = "Adds real-time DSP audio profiles (Nightcore, Slowed + Reverb, Muffled, Lo-Fi, Daycore, Vaporwave, Live Concert, 8D Audio, Bass Boosted, Custom).",
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

        // Hook player TabbedView constructor and tab selection.
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

        // Hook AudioTrack.write(ByteBuffer, int, int) inside DefaultAudioSink / AudioTrackAudioOutput
        // so 8D Audio processes every PCM frame and updates hardware stereo panning in real time.
        runCatching {
            Fingerprint(
                filters = listOf(
                    methodCall(smali = "Landroid/media/AudioTrack;->write(Ljava/nio/ByteBuffer;II)I")
                )
            ).matchAll().forEach { match ->
                match.instructionMatches.reversed().forEach { instMatch ->
                    val inst = instMatch.instruction
                    if (inst is FiveRegisterInstruction) {
                        val trackReg = inst.registerC
                        val bufReg = inst.registerD
                        val sizeReg = inst.registerE
                        match.method.addInstruction(
                            instMatch.index,
                            "invoke-static { v$trackReg, v$bufReg, v$sizeReg }, " +
                                "$EXTENSION_CLASS->onAudioTrackWrite(Landroid/media/AudioTrack;Ljava/nio/ByteBuffer;I)V"
                        )
                    } else if (inst is RegisterRangeInstruction && inst.registerCount >= 3) {
                        val start = inst.startRegister
                        val end = start + 2
                        match.method.addInstruction(
                            instMatch.index,
                            "invoke-static/range { v$start .. v$end }, " +
                                "$EXTENSION_CLASS->onAudioTrackWrite(Landroid/media/AudioTrack;Ljava/nio/ByteBuffer;I)V"
                        )
                    }
                }
            }
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

        // Fallback-hook AudioTrack.getAudioSessionId() inside AudioTrackAudioOutput if present.
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
