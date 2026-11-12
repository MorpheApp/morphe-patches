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
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.settings.PreferenceScreen
import app.morphe.patches.music.misc.settings.settingsPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.patches.music.video.speed.playbackSpeedPatch
import app.morphe.patches.shared.misc.settings.preference.ListPreference

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/music/patches/AudioFxProfilesPatch;"

@Suppress("unused")
val audioFxProfilesPatch = patch(
    name = "Audio FX profiles",
    description = "Adds real-time DSP audio profiles including Nightcore, Slowed + Reverb, Muffled, Lo-Fi, Daycore, Vaporwave, Live Concert, 8D Audio, and Bass Boosted for both online and offline playback.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        playbackSpeedPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)

    execute {
        PreferenceScreen.PLAYER.addPreferences(
            ListPreference("morphe_music_audio_fx_profile")
        )

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
                // After invoke-virtual getAudioSessionId()I + move-result vX
                val moveResultInst = fp.method.implementation!!.instructions.elementAt(matchIndex + 1)
                if (moveResultInst is com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction) {
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
