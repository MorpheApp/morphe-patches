/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4891
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.interaction.sonos

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.patch
import app.morphe.patches.music.interaction.audioducking.AudioFocusChangeFingerprint
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.mediasession.addSetMetadataWithSessionHook
import app.morphe.patches.music.misc.mediasession.mediaSessionHookPatch
import app.morphe.patches.music.misc.settings.PreferenceScreen
import app.morphe.patches.music.misc.settings.settingsPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.fiveRegisters
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val EXTENSION_CLASS = "Lapp/morphe/extension/music/patches/sonos/SonosCastPatch;"

@Suppress("unused")
val sonosCastPatch = patch(
    name = "Sonos Cast",
    description = "Adds an option to stream the audio to Sonos and other UPnP speakers on the same Wi-Fi " +
            "network from the Cast button."
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        mediaSessionHookPatch
    )

    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)

    execute {
        PreferenceScreen.PLAYER.addPreferences(
            SwitchPreference("morphe_music_sonos_cast", summary = true)
        )

        // Show the Cast button even with no Cast devices, as speakers can still be available.
        MediaRouteButtonControllerUpdateFingerprint.let {
            val isAvailableRegister = it.instructionMatches[1].getInstruction<OneRegisterInstruction>().registerA

            // Other code paths that decide the value also jump to this instruction.
            it.method.addInstructionsAtControlFlowLabel(
                it.instructionMatches[2].index,
                """
                    invoke-static { v$isAvailableRegister }, $EXTENSION_CLASS->isCastButtonAvailable(Z)Z
                    move-result v$isAvailableRegister
                """
            )
        }

        // Show the speaker sheet instead of the Cast dialog.
        MdxMediaRouteButtonPerformClickFingerprint.method.addInstructionsWithLabels(
            0,
            """
                invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS->onCastButtonClick(Landroid/view/View;)Z
                move-result v0
                if-eqz v0, :show_cast_dialog
                return v0
                :show_cast_dialog
                nop
            """
        )

        // Keep the phone muted while connected.
        // Before the write hook, which changes the call the class fingerprint matches.
        AudioSinkSetVolumeFingerprint.let {
            val index = it.instructionMatches.first().index

            it.method.replaceInstruction(
                index,
                "invoke-static { ${it.method.fiveRegisters(index)} }, " +
                        "$EXTENSION_CLASS->setVolume(Landroid/media/AudioTrack;F)I"
            )
        }

        // Copy the decoded audio to the stream while connected.
        AudioSinkWriteFingerprint.let {
            val index = it.instructionMatches.first().index

            it.method.replaceInstruction(
                index,
                "invoke-static { ${it.method.fiveRegisters(index)} }, " +
                        "$EXTENSION_CLASS->writeAudio(Landroid/media/AudioTrack;Ljava/nio/ByteBuffer;II)I"
            )
        }

        // Keep playing on the speaker when another app plays on the phone.
        AudioFocusChangeFingerprint.method.addInstructionsWithLabels(
            0,
            """
                invoke-static/range { p0 .. p1 }, $EXTENSION_CLASS->deferAudioFocusChange(Landroid/media/AudioManager${'$'}OnAudioFocusChangeListener;I)Z
                move-result v0
                if-eqz v0, :handle
                return-void
                :handle
                nop
            """
        )

        addSetMetadataWithSessionHook(
            "$EXTENSION_CLASS->onSetMetadata(Landroid/media/session/MediaSession;Landroid/media/MediaMetadata;)V"
        )
    }
}
