package app.morphe.patches.youtube.layout.shortsplayer

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.mapping.resourceMappingPatch
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.patches.youtube.layout.player.fullscreen.openVideosFullscreenHookPatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.navigation.navigationBarHookPatch
import app.morphe.patches.youtube.misc.playservice.is_20_39_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.MainActivityOnCreateFingerprint
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstruction
import app.morphe.util.indexOfFirstInstructionOrThrow
import app.morphe.util.indexOfFirstInstructionReversedOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/youtube/patches/OpenShortsInRegularPlayerPatch;"

@Suppress("unused")
val openShortsInRegularPlayerPatch = bytecodePatch(
    name = "Open Shorts in regular player",
    description = "Adds options to open Shorts in the regular video player.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        openVideosFullscreenHookPatch,
        navigationBarHookPatch,
        versionCheckPatch,
        resourceMappingPatch
    )

    compatibleWith(
        "com.google.android.youtube"(
            "20.14.43",
            "20.21.37",
            "20.31.42",
            "20.46.41",
        )
    )

    execute {
        PreferenceScreen.SHORTS.addPreferences(
            ListPreference("morphe_shorts_player_type")
        )

        // Activity is used as the context to launch an Intent.
        MainActivityOnCreateFingerprint.method.addInstruction(
            0,
            "invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS_DESCRIPTOR->" +
                    "setMainActivity(Landroid/app/Activity;)V",
        )

        // Find the obfuscated method name for PlaybackStartDescriptor.videoId()
        val (videoIdStartMethod, videoIdIndex) = if (is_20_39_or_greater) {
            WatchPanelVideoIdFingerprint.let {
                it.method to it.instructionMatches.last().index
            }
        } else {
            PlaybackStartFeatureFlagFingerprint.let {
                it.method to it.instructionMatches.first().index
            }
        }
        val playbackStartVideoIdMethodName = navigate(videoIdStartMethod).to(videoIdIndex).stop().name

        ShortsPlaybackIntentFingerprint.method.addInstructionsWithLabels(
            0,
            """
                move-object/from16 v0, p1
                
                invoke-virtual { v0 }, Lcom/google/android/libraries/youtube/player/model/PlaybackStartDescriptor;->$playbackStartVideoIdMethodName()Ljava/lang/String;
                move-result-object v1
                invoke-static { v1 }, $EXTENSION_CLASS_DESCRIPTOR->openShort(Ljava/lang/String;)Z
                move-result v1
                if-eqz v1, :disabled
                return-void
                
                :disabled
                nop
            """
        )

        // Fix issue with back button exiting the app instead of minimizing the player.
        // Without this change this issue can be difficult to reproduce, but seems to occur
        // most often with 'open video in regular player' and not open in fullscreen player.
        ExitVideoPlayerFingerprint.method.apply {
            // Method call for Activity.finish()
            val finishIndexFirst = indexOfFirstInstructionOrThrow {
                val reference = getReference<MethodReference>()
                reference?.name == "finish"
            }

            // Second Activity.finish() call. Has been present since 19.x but started
            // to interfere with back to exit fullscreen around 20.47.
            val finishIndexSecond = indexOfFirstInstruction(finishIndexFirst + 1) {
                val reference = getReference<MethodReference>()
                reference?.name == "finish"
            }
            val getBooleanFieldIndex = indexOfFirstInstructionReversedOrThrow(finishIndexSecond) {
                opcode == Opcode.IGET_BOOLEAN
            }
            val booleanRegister = getInstruction<TwoRegisterInstruction>(getBooleanFieldIndex).registerA

            addInstructions(
                getBooleanFieldIndex + 1,
                """
                    invoke-static { v$booleanRegister }, $EXTENSION_CLASS_DESCRIPTOR->overrideBackPressToExit(Z)Z    
                    move-result v$booleanRegister
                """
            )

            // Index of PlayerType.isWatchWhileMaximizedOrFullscreen()
            val index = indexOfFirstInstructionReversedOrThrow(finishIndexFirst, Opcode.MOVE_RESULT)
            val register = getInstruction<OneRegisterInstruction>(index).registerA

            addInstructions(
                index + 1,
                """
                    invoke-static { v$register }, $EXTENSION_CLASS_DESCRIPTOR->overrideBackPressToExit(Z)Z    
                    move-result v$register
                """
            )
        }
    }
}
