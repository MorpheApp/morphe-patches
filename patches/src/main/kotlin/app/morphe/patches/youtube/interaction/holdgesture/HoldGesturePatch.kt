/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5099
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.interaction.holdgesture

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.patch
import app.morphe.patches.shared.misc.settings.preference.InputType
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference.Sorting
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.shared.misc.settings.preference.TextPreference
import app.morphe.patches.youtube.layout.sponsorblock.ControlsOverlayFingerprint
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playercontrols.playerControlsOverlayVisibilityPatch
import app.morphe.patches.youtube.misc.playertype.playerTypeHookPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patches.youtube.video.information.videoInformationPatch
import app.morphe.patches.youtube.video.speed.custom.TapAndHoldSpeedFingerprint
import app.morphe.patches.youtube.video.speed.custom.customPlaybackSpeedPatch
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/holdgesture/HoldGestureController;"

@Suppress("unused")
val holdGesturePatch = patch(
    name = "Hold gesture",
    description = "Adds options to change the playback speed and to keep it " +
        "by dragging after tapping and holding the video player.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        videoInformationPatch,
        playerTypeHookPatch,
        playerControlsOverlayVisibilityPatch,
        // Sets the speed of tap and hold and allows speeds above 2x.
        customPlaybackSpeedPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.PLAYER.addPreferences(
            PreferenceScreenPreference(
                key = "morphe_hold_gesture_screen",
                sorting = Sorting.UNSORTED,
                preferences = setOf(
                    SwitchPreference(
                        key = "morphe_hold_gesture",
                        summary = true,
                        tag = "app.morphe.extension.youtube.settings.preference.HoldGestureSwitchPreference"
                    ),
                    SwitchPreference("morphe_hold_gesture_haptic_feedback", summary = true),
                    SwitchPreference("morphe_hold_gesture_speed_swipe", summary = true),
                    ListPreference("morphe_hold_gesture_speed_step"),
                    ListPreference("morphe_hold_gesture_speed_sensitivity"),
                    TextPreference("morphe_hold_gesture_speed_min", inputType = InputType.NUMBER_DECIMAL),
                    TextPreference("morphe_hold_gesture_speed_max", inputType = InputType.NUMBER_DECIMAL),
                    SwitchPreference("morphe_hold_gesture_lock", summary = true),
                    SwitchPreference("morphe_hold_gesture_lock_badge_always", summary = true),
                    SwitchPreference("morphe_hold_gesture_unlock_on_video_change", summary = true),
                )
            )
        )

        // Take over when a long press is reached. Other patches change this method too,
        // so match it again before changing it.
        TapAndHoldSpeedFingerprint.let {
            it.clearMatch()
            it.method.apply {
                // The last check before the app plays faster: "already playing faster".
                // Asking here and returning leaves out both what the app does for tap and hold
                // and what it does for a long press without it, which shows the player controls.
                val alreadyFasterIndex = it.instructionMatches[5].index
                val register = getInstruction<OneRegisterInstruction>(alreadyFasterIndex).registerA

                addInstructionsWithLabels(
                    alreadyFasterIndex,
                    """
                        if-nez v$register, :original
                        invoke-static { }, $EXTENSION_CLASS->onTapAndHold()Z
                        move-result v$register
                        if-eqz v$register, :original
                        return-void
                        :original
                        nop
                    """
                )
            }
        }

        // Add the overlay to the same player layout as the SponsorBlock overlay.
        ControlsOverlayFingerprint.let {
            it.clearMatch() // Parent fingerprint is shared and may have changed.
            it.method.apply {
                val checkCastIndex = it.instructionMatches.last().index
                val frameLayoutRegister = getInstruction<OneRegisterInstruction>(checkCastIndex).registerA
                addInstruction(
                    checkCastIndex + 1,
                    "invoke-static { v$frameLayoutRegister }, $EXTENSION_CLASS->initialize(Landroid/view/ViewGroup;)V"
                )
            }
        }
    }
}
