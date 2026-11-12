/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.video.audio

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.patch
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.settings.PreferenceScreen
import app.morphe.patches.music.misc.settings.settingsPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.patches.music.video.speed.playbackSpeedPatch
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.patches.youtube.video.audio.AudioTrackSessionIdFingerprint
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

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

        AudioTrackSessionIdFingerprint.let {
            it.method.apply {
                val resultIndex = it.instructionMatches.last().index
                val register = getInstruction<OneRegisterInstruction>(resultIndex).registerA
                addInstruction(
                    resultIndex + 1,
                    "invoke-static { v$register }, $EXTENSION_CLASS->onAudioSessionId(I)V"
                )
            }
        }
    }
}
