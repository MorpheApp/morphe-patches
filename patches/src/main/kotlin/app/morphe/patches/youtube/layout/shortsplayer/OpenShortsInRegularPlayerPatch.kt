package app.morphe.patches.youtube.layout.shortsplayer

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.OpcodesFilter
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.methodCall
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
import app.morphe.util.findInstructionIndicesReversedOrThrow
import app.morphe.util.getMutableMethod
import app.morphe.util.getReference
import app.morphe.util.registersUsed
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
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

        val playbackStartVideoIdMethodName : String
        PlaybackStartDescriptorToStringFingerprint.let {
            playbackStartVideoIdMethodName = it.instructionMatches[1]
                .getInstruction<ReferenceInstruction>()
                .getReference<MethodReference>()!!
                .getMutableMethod()
                .name
        }

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
        //
        // Note: this patch must be applied on the 'if checks' outermost to the finish() call
        // to avoid blocking the player minimization code after pressing the back button.
        ExitVideoPlayerFingerprint.method.apply {
            var isTopFinishInvoke = false
            val lastFinishCallOpcodeMatchFor21xx = Fingerprint(
                filters = OpcodesFilter.opcodesToFilters(
                    Opcode.IF_NEZ,
                    Opcode.INVOKE_INTERFACE,
                    Opcode.MOVE_RESULT_OBJECT,
                    Opcode.CHECK_CAST,
                    Opcode.IGET_OBJECT,
                    Opcode.IGET_OBJECT,
                    Opcode.SGET_OBJECT,
                    Opcode.IF_EQ,
                    Opcode.INVOKE_INTERFACE
                )
            ).match(this).instructionMatches.firstOrNull()
            val patchMap = mutableMapOf<Int, Int>()
            fun validateInstruction(index: Int): Int {
                val targetInstruction = getInstruction(index)

                if (targetInstruction.opcode == Opcode.IF_EQZ || targetInstruction.opcode == Opcode.IF_NEZ) {
                    return targetInstruction.registersUsed[0]
                }

                return -1
            }

            findInstructionIndicesReversedOrThrow(
                methodCall(name = "finish", parameters = listOf())
            ).forEach { index ->
                var targetInstructionIndex = -1
                var targetInstructionRegister = -1

                // Iterate over previous 2 indexes, to detect the target conditional instruction to
                // patch, starting from the first (with top-to-bottom sorting) finish() call.
                for (currentTargetSubIndex in 1..2) {
                    targetInstructionIndex = index - currentTargetSubIndex
                    targetInstructionRegister = validateInstruction(targetInstructionIndex)
                    if (targetInstructionRegister > -1) {
                        break
                    }
                }

                // If previous attempts fail to find a conditional instruction before the last (with top-to-bottom sorting)
                // finish() call (mean that you're targeting version 21.xx), then this further attempt detect if a
                // return-void preceding the instruction and will apply a different searching logic.
                if (targetInstructionRegister == -1 && !isTopFinishInvoke && lastFinishCallOpcodeMatchFor21xx != null) {
                    targetInstructionIndex = lastFinishCallOpcodeMatchFor21xx.index
                    targetInstructionRegister = validateInstruction(targetInstructionIndex)
                }

                if (targetInstructionRegister > -1) {
                    patchMap[targetInstructionIndex] = targetInstructionRegister

                    isTopFinishInvoke = true
                }
            }

            // Not all versions can be always patched with a reversed code indices. Then the following
            // code will check which patch have the lower index (to apply it as last).
            if (patchMap.count() == 2) {
                patchMap.toSortedMap(compareByDescending { it }).forEach { (index, register) ->
                    addInstructionsAtControlFlowLabel(
                        index,
                        """
                            invoke-static { v$register }, $EXTENSION_CLASS->overrideBackPressToExit(Z)Z
                            move-result v$register      
                        """
                    )
                }
            } else {
                throw PatchException("Failed: not all finish() invokes has been patched")
            }
        }
    }
}
