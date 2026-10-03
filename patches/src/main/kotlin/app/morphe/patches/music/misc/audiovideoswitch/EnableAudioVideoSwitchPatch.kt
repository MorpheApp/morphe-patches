/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3471
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.misc.audiovideoswitch

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.music.layout.hide.general.AudioVideoSwitchPillContainerFingerprint
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.settings.PreferenceScreen
import app.morphe.patches.music.misc.settings.settingsPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.patches.music.video.information.musicVideoInformationPatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import java.util.logging.Logger

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/music/patches/EnableAudioVideoSwitchPatch;"

@Suppress("unused")
val enableAudioVideoSwitchPatch = bytecodePatch(
    name = "Enable audio/video switch",
    description = "Adds an option to switch between the audio and the video version of a track by redirecting videoId."
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        musicVideoInformationPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)

    execute {
        val log by lazy { Logger.getLogger(this::class.java.name) }

        PreferenceScreen.PLAYER.addPreferences(
            SwitchPreference("morphe_music_enable_audio_video_switch", summary = true)
        )

        AudioVideoSwitchPillContainerFingerprint.matchAll().forEach { match ->
            val method = match.method
            val instructions = method.implementation!!.instructions

            var moveResultIndex = match.instructionMatches.last().index
            while (moveResultIndex < instructions.size &&
                instructions[moveResultIndex].opcode != Opcode.MOVE_RESULT_OBJECT
            ) {
                moveResultIndex++
            }
            if (moveResultIndex >= instructions.size) {
                log.warning("Audio/video switch pill: view register not found.")
                return@forEach
            }

            val viewRegister =
                method.getInstruction<OneRegisterInstruction>(moveResultIndex).registerA

            method.addInstruction(
                moveResultIndex + 1,
                "invoke-static { v$viewRegister }, $EXTENSION_CLASS->" +
                        "installAudioVideoSwitchInterceptor(Landroid/view/View;)V"
            )
        }
    }
}
