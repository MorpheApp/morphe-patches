/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4891
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.interaction.sonos

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.patch
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.mediasession.addSetMetadataWithSessionHook
import app.morphe.patches.music.misc.settings.PreferenceScreen
import app.morphe.patches.music.misc.settings.settingsPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction

private const val EXTENSION_CLASS = "Lapp/morphe/extension/music/patches/sonos/SonosCastManager;"

@Suppress("unused")
val sonosCastPatch = patch(
    name = "Sonos Cast",
    description = "Enables direct, lossless PCM audio casting to Sonos and UPnP speakers from the in-app Cast button."
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch
    )

    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)

    execute {
        PreferenceScreen.PLAYER.addPreferences(
            SwitchPreference("morphe_music_sonos_cast", summary = true)
        )

        // 1. Hook Cast button performClick
        MediaRouteButtonPerformClickFingerprint.let {
            it.method.apply {
                addInstructions(
                    0,
                    """
                        invoke-static { p0 }, $EXTENSION_CLASS->handleCastButtonClick(Landroid/view/View;)Z
                        move-result v0
                        if-eqz v0, :cond_sonos_handled
                        const/4 v0, 0x1
                        return v0
                        :cond_sonos_handled
                    """.trimIndent()
                )
            }
        }

        // 2. Intercept AudioTrack.write in ExoPlayer's DefaultAudioSink
        AudioTrackWriteFingerprint.let {
            it.method.apply {
                val index = it.instructionMatches.first().index
                val insn = getInstruction<FiveRegisterInstruction>(index)
                val trackReg = insn.registerC
                val bufReg = insn.registerD
                val sizeReg = insn.registerE
                val modeReg = insn.registerF

                replaceInstruction(
                    index,
                    "invoke-static { v$trackReg, v$bufReg, v$sizeReg, v$modeReg }, " +
                            "$EXTENSION_CLASS->audioTrackWrite(Landroid/media/AudioTrack;Ljava/nio/ByteBuffer;II)I"
                )
            }
        }

        // 3. Suppress phone speaker unmute while connected
        AudioSinkMuteFingerprint.let {
            it.method.apply {
                addInstructions(
                    0,
                    """
                        invoke-static { }, $EXTENSION_CLASS->isConnected()Z
                        move-result v0
                        if-eqz v0, :cond_sonos_unmute
                        return-void
                        :cond_sonos_unmute
                    """.trimIndent()
                )
            }
        }

        // 4. Hook live MediaMetadata updates
        addSetMetadataWithSessionHook("$EXTENSION_CLASS->onMediaMetadata(Landroid/media/MediaMetadata;)V")
    }
}
