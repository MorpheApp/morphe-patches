/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4982
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.interaction.trackswipe

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.findFreeRegister
import app.morphe.patcher.util.findInstructionIndicesReversed
import app.morphe.patcher.util.findInstructionIndicesReversedOrThrow
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.settings.PreferenceScreen
import app.morphe.patches.music.misc.settings.settingsPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction

private const val EXTENSION_CLASS = "Lapp/morphe/extension/music/patches/DisableSwipeToChangeTrackPatch;"

@Suppress("unused")
val disableSwipeToChangeTrackPatch = patch(
    name = "Disable swipe to change track",
    description = "Adds options to disable changing the track by swiping left or right on the player or the miniplayer."
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch
    )

    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)

    execute {
        PreferenceScreen.PLAYER.addPreferences(
            SwitchPreference("morphe_music_disable_player_swipe_to_change_track", summary = true)
        )
        PreferenceScreen.addMiniplayerPreferences(
            SwitchPreference("morphe_music_disable_miniplayer_swipe_to_change_track", summary = true)
        )

        // The controller turns the swipe of each pager on whenever it shows the pager.
        // The extension decides it instead, from the pager and the value the app sets.
        // The value is put in a free register, as the app can use the register of the value again after the call.
        val setUserInputEnabledMethod = ViewPagerSetUserInputEnabledFingerprint.originalMethod
        var hookCount = 0

        TrackPagerControllerConstructorFingerprint.classDef.methods.forEach { method ->
            method.findInstructionIndicesReversed(methodCall(reference = setUserInputEnabledMethod)).forEach { index ->
                val call = method.getInstruction<FiveRegisterInstruction>(index)
                val pagerRegister = call.registerC
                val enabledRegister = call.registerD
                val freeRegister = method.findFreeRegister(index, pagerRegister, enabledRegister)
                if (freeRegister > 15) {
                    throw PatchException("No 4-bit free register to change the swipe of the track pager in $method")
                }

                method.replaceInstruction(
                    index,
                    "invoke-virtual { v$pagerRegister, v$freeRegister }, $setUserInputEnabledMethod"
                )
                method.addInstructionsAtControlFlowLabel(
                    index,
                    """
                        invoke-static { v$pagerRegister, v$enabledRegister }, $EXTENSION_CLASS->isUserInputEnabled(Landroid/view/View;Z)Z
                        move-result v$freeRegister
                    """
                )
                hookCount++
            }
        }

        if (hookCount == 0) throw PatchException("Could not find where the track pagers enable their swipe")

        // The swipe of a new pager is on, so it is also set when the controller keeps each pager.
        TrackPagerControllerConstructorFingerprint.method.apply {
            findInstructionIndicesReversedOrThrow(
                fieldAccess(opcode = Opcode.IPUT_OBJECT, definingClass = "this", type = VIEW_PAGER_CLASS)
            ).forEach { index ->
                val pagerRegister = getInstruction<TwoRegisterInstruction>(index).registerA
                val freeRegister = findFreeRegister(index + 1, pagerRegister)
                if (freeRegister > 15) {
                    throw PatchException("No 4-bit free register to change the swipe of the new track pager")
                }

                addInstructions(
                    index + 1,
                    """
                        const/4 v$freeRegister, 0x1
                        invoke-static { v$pagerRegister, v$freeRegister }, $EXTENSION_CLASS->isUserInputEnabled(Landroid/view/View;Z)Z
                        move-result v$freeRegister
                        invoke-virtual { v$pagerRegister, v$freeRegister }, $setUserInputEnabledMethod
                    """
                )
            }
        }
    }
}
