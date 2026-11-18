/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3471
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.misc.audiovideoswitch

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.patch
import app.morphe.patches.music.layout.hide.general.AudioVideoSwitchPillContainerFingerprint
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.settings.PreferenceScreen
import app.morphe.patches.music.misc.settings.settingsPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.patches.music.video.information.musicVideoInformationPatch
import app.morphe.patches.music.video.playerresponse.Hook
import app.morphe.patches.music.video.playerresponse.addPlayerResponseMethodHook
import app.morphe.patches.music.video.playerresponse.musicPlayerResponseMethodHookPatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.shared.misc.settings.preference.noTitleUnsortedPreferenceCategory
import app.morphe.util.findInstructionIndicesReversedOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/music/patches/EnableAudioVideoSwitchPatch;"
internal const val AUTO_SONG_VERSION_CLASS =
    "Lapp/morphe/extension/music/patches/AutoSongVersionPatch;"

val enableAudioVideoSwitchPatch = patch(
    name = "Enable audio/video switch",
    description = "Adds an option to switch between the audio and the video version of a track by redirecting videoId."
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        musicVideoInformationPatch,
        musicPlayerResponseMethodHookPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)

    execute {
        PreferenceScreen.PLAYER.addPreferences(
            noTitleUnsortedPreferenceCategory(
                SwitchPreference("morphe_music_enable_audio_video_switch", summary = true),
                SwitchPreference("morphe_music_auto_song_version", summary = true)
            )
        )

        AudioVideoSwitchPillContainerFingerprint.matchAll().forEach { match ->
            match.method.apply {
                val moveResultIndex = match.instructionMatches.last().index
                val viewRegister = getInstruction<OneRegisterInstruction>(moveResultIndex).registerA

                addInstruction(
                    moveResultIndex + 1,
                    "invoke-static { v$viewRegister }, $EXTENSION_CLASS->" +
                            "installAudioVideoSwitchInterceptor(Landroid/view/View;)V"
                )
            }
        }

        installAudioVideoSwitchQueue(resolveAudioVideoQueue())

        addPlayerResponseMethodHook(
            Hook.VideoIdAndPlaylistId(
                "$AUTO_SONG_VERSION_CLASS->trackPlaylist(Ljava/lang/String;Ljava/lang/String;I)V"
            )
        )

        AccountPreferencesConstructorFingerprint.method.apply {
            findInstructionIndicesReversedOrThrow(Opcode.RETURN_VOID).forEach { index ->
                addInstruction(
                    index,
                    "invoke-static { p0 }, $AUTO_SONG_VERSION_CLASS->" +
                            "setAccountPreferences(Landroid/content/SharedPreferences;)V"
                )
            }
        }
    }
}
