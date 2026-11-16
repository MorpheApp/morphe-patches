/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.layout.player.fullscreen

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.findFreeRegister
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference.Sorting
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playservice.is_20_40_or_greater
import app.morphe.patches.youtube.misc.playservice.is_20_51_or_greater
import app.morphe.patches.youtube.misc.playservice.is_21_28_or_greater
import app.morphe.patches.youtube.misc.playservice.is_21_30_or_greater
import app.morphe.patches.youtube.misc.playservice.is_21_36_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.insertLiteralOverride
import app.morphe.util.toPublicAccessFlags
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val DISABLE_FULLSCREEN_GESTURES_CLASS =
    "Lapp/morphe/extension/youtube/patches/DisableFullscreenGesturesPatch;"

val disableFullscreenGesturesPatch = patch(
    name = "Disable fullscreen gestures",
    description = "Adds options to selectively disable gestures for entering and exiting fullscreen mode, and to disable pinch-to-zoom.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        versionCheckPatch
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        val gesturePreferences = mutableSetOf(
            SwitchPreference("morphe_disable_fullscreen_pulled_up_gesture"),
            SwitchPreference("morphe_disable_fullscreen_dragged_down_gesture"),
            SwitchPreference("morphe_disable_fullscreen_sliding_down_gesture"),
            SwitchPreference("morphe_disable_fullscreen_zoom_gesture")
        )
        if (is_21_36_or_greater) {
            gesturePreferences += SwitchPreference("morphe_restore_pinch_to_zoom")
        }
        PreferenceScreen.PLAYER.addPreferences(
            PreferenceScreenPreference(
                key = "morphe_disable_fullscreen_gestures",
                sorting = Sorting.UNSORTED,
                preferences = gesturePreferences
            )
        )

        val playerDragGestureTypeMethod = PlayerDragGestureTypeFingerprint.method
        PlayerDragGestureTypeFingerprint.classDef.methods.apply {
            removeIf {
                it != playerDragGestureTypeMethod &&
                        it.accessFlags == playerDragGestureTypeMethod.accessFlags &&
                        it.name == playerDragGestureTypeMethod.name &&
                        it.parameters == playerDragGestureTypeMethod.parameters
            }
            playerDragGestureTypeMethod.let { gestureMethod ->
                gestureMethod.accessFlags = gestureMethod.accessFlags.toPublicAccessFlags()
            }
        }

        PlayerDragGestureInitFingerprint.apply {
            method.apply {
                val index = instructionMatches.last().index
                val free = findFreeRegister(index)

                method.addInstructionsAtControlFlowLabel(
                    index,
                    """
                        invoke-static { p4 }, ${playerDragGestureTypeMethod.definingClass}->${playerDragGestureTypeMethod.name}(I)Ljava/lang/String;
                        move-result-object v$free
                        invoke-static { v$free }, $DISABLE_FULLSCREEN_GESTURES_CLASS->disableFullscreenGestures(Ljava/lang/String;)Z
                        move-result v$free
                        if-eqz v$free, :disable_fullscreen_gesture
                        const/4 v$free, 0x0
                        return v$free
                        :disable_fullscreen_gesture
                        nop
                    """
                )
            }
        }

        VideoZoomScaleBeginFingerprint.method.addInstructionsWithLabels(
            0,
            """
                invoke-static { p1 }, $DISABLE_FULLSCREEN_GESTURES_CLASS->disableZoomGestureBegin(Landroid/view/ScaleGestureDetector;)Z
                move-result v0
                if-eqz v0, :allow_zoom
                const/4 v0, 0x0
                return v0
                :allow_zoom
                nop
            """
        )

        // The zoom begins only after the touch can no longer be a tap with two fingers.
        VideoZoomTouchFingerprint.match(VideoZoomScaleBeginFingerprint.classDef).method.addInstruction(
            0,
            "invoke-static { p1, p2 }, $DISABLE_FULLSCREEN_GESTURES_CLASS->" +
                "onZoomTouchEvent(Landroid/view/View;Landroid/view/MotionEvent;)V"
        )

        // Shorts pinch-to-zoom is an A/B test that is off by default. Turn it on
        // unless pinch-to-zoom is disabled. 21.28 and 21.29 read both flags.
        if (is_20_51_or_greater && !is_21_30_or_greater) {
            ShortsPinchToZoomLegacyFeatureFlagFingerprint.let {
                it.method.insertLiteralOverride(
                    it.instructionMatches.first().index,
                    "$DISABLE_FULLSCREEN_GESTURES_CLASS->enableShortsZoomGesture(Z)Z"
                )
            }
        }
        if (is_21_28_or_greater) {
            ShortsPinchToZoomFeatureFlagFingerprint.let {
                it.method.insertLiteralOverride(
                    it.instructionMatches.first().index,
                    "$DISABLE_FULLSCREEN_GESTURES_CLASS->enableShortsZoomGesture(Z)Z"
                )
            }
        }

        if (is_21_36_or_greater) {
            // Native pinch scales inside the 16:9 PlayerView (Nx HUD, sidebars
            // stay). Scale that view with the gesture so the bars are eaten.
            // Do not force YouTube flag 45698813; that hides the seekbar and is
            // not required for the view transform.
            VideoZoomScaleBeginFingerprint.classDef.methods.forEach { method ->
                when (method.name) {
                    "onScale" -> method.addInstructions(
                        0,
                        "invoke-static { p1 }, $DISABLE_FULLSCREEN_GESTURES_CLASS->" +
                            "onPinchScale(Landroid/view/ScaleGestureDetector;)V"
                    )
                    "onScaleEnd" -> method.addInstructions(
                        0,
                        "invoke-static { p1 }, $DISABLE_FULLSCREEN_GESTURES_CLASS->" +
                            "onPinchScaleEnd(Landroid/view/ScaleGestureDetector;)V"
                    )
                }
            }
            YouTubePlayerViewOnLayoutFingerprint.let {
                it.method.addInstructionsAtControlFlowLabel(
                    it.instructionMatches.first().index,
                    "invoke-static { p0 }, $DISABLE_FULLSCREEN_GESTURES_CLASS->" +
                        "onPlayerViewLayout(Landroid/view/View;)V"
                )
            }
            YouTubePlayerOverlaysLayoutConstructorFingerprint.matchAll().forEach {
                it.method.addInstruction(
                    it.instructionMatches.first().index,
                    "invoke-static { p0 }, $DISABLE_FULLSCREEN_GESTURES_CLASS->" +
                        "attachPlayerOverlay(Landroid/view/View;)V"
                )
            }
        } else if (is_20_40_or_greater) {
            FullscreenGestureZoomFingerprint.apply {
                method.apply {
                    val instructionIndex = instructionMatches[9].index
                    val instructionRegister = getInstruction<OneRegisterInstruction>(
                        instructionIndex
                    ).registerA

                    addInstructions(
                        instructionIndex + 1,
                        """
                            invoke-static { v$instructionRegister }, $DISABLE_FULLSCREEN_GESTURES_CLASS->disableBrokenFullscreenZoomFlag(Z)Z
                            move-result v$instructionRegister
                        """
                    )
                }
            }
        }
    }
}
