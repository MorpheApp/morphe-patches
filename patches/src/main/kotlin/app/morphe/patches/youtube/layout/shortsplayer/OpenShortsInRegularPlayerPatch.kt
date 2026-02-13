package app.morphe.patches.youtube.layout.shortsplayer

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.shared.misc.mapping.resourceMappingPatch
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.patches.youtube.layout.player.fullscreen.openVideosFullscreenHookPatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.navigation.navigationBarHookPatch
import app.morphe.patches.youtube.misc.playservice.is_21_07_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patches.youtube.shared.YouTubeActivityOnCreateFingerprint
import app.morphe.patches.youtube.video.information.PlaybackStartDescriptorToStringFingerprint
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.findFreeRegister
import app.morphe.util.findInstructionIndicesReversedOrThrow
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstruction
import app.morphe.util.indexOfFirstInstructionOrThrow
import app.morphe.util.indexOfFirstInstructionReversedOrThrow
import app.morphe.util.registersUsed
import com.android.tools.smali.dexlib2.Opcode
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

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.SHORTS.addPreferences(
            ListPreference("morphe_shorts_player_type")
        )

        // Activity is used as the context to launch an Intent.
        YouTubeActivityOnCreateFingerprint.method.addInstruction(
            0,
            "invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS_DESCRIPTOR->" +
                    "setMainActivity(Landroid/app/Activity;)V",
        )

        val playbackStartVideoIdMethodName : String
        PlaybackStartDescriptorToStringFingerprint.let {
            playbackStartVideoIdMethodName = navigate(it.method).to(it.instructionMatches[1].index).stop().name
        }

        ShortsPlaybackIntentFingerprint.method.addInstructionsWithLabels(
            0,
            """
                move-object/from16 v0, p1
                
                invoke-virtual { v0 }, ${PlaybackStartDescriptorToStringFingerprint.classDef}->$playbackStartVideoIdMethodName()Ljava/lang/String;
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
        ExitVideoPlayerFingerprint.method.apply {
            // TODO: Check if this logic works for older app targets as well.
            if (is_21_07_or_greater) {
                findInstructionIndicesReversedOrThrow(
                    methodCall(name = "finish", parameters = listOf())
                ).forEach {index ->
                    val returnIndex = indexOfFirstInstructionOrThrow(
                        index, Opcode.RETURN_VOID
                    )

                    if (returnIndex == this.implementation!!.instructions.lastIndex) {
                        val freeRegister = findFreeRegister(index)

                        // Jumps to last index
                        addInstructionsAtControlFlowLabel(
                            index,
                            """
                                invoke-static { }, $EXTENSION_CLASS_DESCRIPTOR->overrideBackPressToExit()Z
                                move-result v$freeRegister      
                                if-eqz v$freeRegister, :doNotCallActivityFinish
                                return-void   
                                :doNotCallActivityFinish
                                nop      
                            """
                        )
                    } else {
                        // Must check free register after the return index.
                        val freeRegister = findFreeRegister(returnIndex + 1)

                        addInstructionsAtControlFlowLabel(
                            index,
                            """
                                invoke-static { }, $EXTENSION_CLASS_DESCRIPTOR->overrideBackPressToExit()Z
                                move-result v$freeRegister      
                                if-eqz v$freeRegister, :doNotCallActivityFinish
                            """, ExternalLabel(
                                "doNotCallActivityFinish",
                                getInstruction(returnIndex + 1)
                            )
                        )
                    }
                }
                return@apply
            }

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

            // Surround first activity.finish() and return-void with conditional check.
            val returnVoidIndex = indexOfFirstInstructionOrThrow(
                finishIndexFirst, Opcode.RETURN_VOID
            )
            // Find free register using index after return void (new control flow path added below).
            val freeRegister = findFreeRegister(
                returnVoidIndex + 1,
                // Exclude all registers used by only instruction we will skip over.
                getInstruction(finishIndexFirst).registersUsed
            )

            addInstructionsAtControlFlowLabel(
                finishIndexFirst,
                """
                    invoke-static { }, $EXTENSION_CLASS_DESCRIPTOR->overrideBackPressToExit()Z
                    move-result v$freeRegister
                    if-eqz v$freeRegister, :doNotCallActivityFinish
                """,
                ExternalLabel(
                    "doNotCallActivityFinish",
                    getInstruction(returnVoidIndex + 1)
                )
            )
        }
    }
}
