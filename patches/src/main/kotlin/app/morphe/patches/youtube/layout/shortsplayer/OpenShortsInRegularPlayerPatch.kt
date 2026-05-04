/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.layout.shortsplayer

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.all.misc.resources.resourceMappingPatch
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.patches.youtube.layout.player.fullscreen.openVideosFullscreenHookPatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.navigation.navigationBarHookPatch
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patches.youtube.shared.YouTubeActivityOnCreateFingerprint
import app.morphe.patches.youtube.video.information.PlaybackStartDescriptorToStringFingerprint
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstructionReversed
import app.morphe.util.indexOfFirstInstructionReversedOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val EXTENSION_CLASS =
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
            "invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS->" +
                    "setMainActivity(Landroid/app/Activity;)V",
        )

        val playbackStartVideoIdMethodName = PlaybackStartDescriptorToStringFingerprint
            .instructionMatches[1].getMethodCalled().name

        ShortsPlaybackIntentFingerprint.method.addInstructionsWithLabels(
            0,
            """
                move-object/from16 v0, p1
                
                invoke-virtual { v0 }, ${PlaybackStartDescriptorToStringFingerprint.classDef}->$playbackStartVideoIdMethodName()Ljava/lang/String;
                move-result-object v1
                invoke-static { v1 }, $EXTENSION_CLASS->openShort(Ljava/lang/String;)Z
                move-result v1
                if-eqz v1, :disabled
                return-void
                
                :disabled
                nop
            """
        )

        // Fix issue with back button exiting the app instead of minimizing the player.
        // Note: this patch must be applied on the conditional instructions that contains the return
        // instruction, to avoid to block the code that minimize the video player.
        ExitVideoPlayerFingerprint.method.apply {
            val expectedChanges = 2
            var changesMade = 0
            var finishIndex = this.instructions.size

            while (true) {
                finishIndex = indexOfFirstInstructionReversed(finishIndex - 1) {
                    val reference = getReference<MethodReference>()
                    reference?.name == "finish" && reference.parameterTypes.isEmpty()
                }
                if (finishIndex < 0) {
                    break
                }

                val finishLabels = getInstruction(finishIndex).location.labels
                val equalsIndex = if (finishLabels.isNotEmpty()) {
                    // Find conditional instruction that jumps to this instruction.
                    indexOfFirstInstructionReversedOrThrow(finishIndex - 1) {
                        if (this !is BuilderOffsetInstruction) {
                            return@indexOfFirstInstructionReversedOrThrow false
                        }
                        val labels = this.target.location.labels
                        labels.any { finishLabels.contains(it) }
                    }
                } else {
                    indexOfFirstInstructionReversedOrThrow(finishIndex) {
                        opcode == Opcode.IF_EQZ || opcode == Opcode.IF_NEZ
                    }
                }

                val register = getInstruction<OneRegisterInstruction>(equalsIndex).registerA
                addInstructionsAtControlFlowLabel(
                    equalsIndex,
                    """
                        invoke-static { v$register }, $EXTENSION_CLASS->overrideBackPressToExit(Z)Z
                        move-result v$register      
                    """
                )
                changesMade++
            }

            if (changesMade != expectedChanges) {
                throw PatchException("Expected $expectedChanges changes but instead found: $changesMade")
            }
        }
    }
}
