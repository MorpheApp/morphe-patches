/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.swipecontrols.controller.gesture.core;

import android.content.Context;
import android.util.TypedValue;

import androidx.annotation.Nullable;

import app.morphe.extension.youtube.patches.VideoInformation;
import app.morphe.extension.youtube.swipecontrols.controller.AudioVolumeController;
import app.morphe.extension.youtube.swipecontrols.controller.ScreenBrightnessController;
import app.morphe.extension.youtube.swipecontrols.misc.ScrollDistanceHelper;
import app.morphe.extension.youtube.swipecontrols.misc.SwipeControlsOverlay;
import app.morphe.extension.youtube.swipecontrols.misc.SwipeControlsUtils;

/**
 * Handles scrolling of volume, brightness, and playback speed, adjusts them using the provided controllers and updates the overlay.
 */
public final class VolumeAndBrightnessScroller {

    /**
     * Minimum playback speed of the speed gesture, multiplied by 100.
     */
    private static final int MINIMUM_SPEED_INT = 25;

    /**
     * Maximum playback speed of the speed gesture, multiplied by 100.
     */
    private static final int MAXIMUM_SPEED_INT = 800;

    private final ScrollDistanceHelper volumeScroller;
    private final ScrollDistanceHelper brightnessScroller;
    private final ScrollDistanceHelper speedScroller;

    /**
     * @param context            Context to create the scrollers in.
     * @param volumeController   Volume controller instance. If null, volume control is disabled.
     * @param screenController   Screen brightness controller instance. If null, brightness control is disabled.
     * @param overlayController  Overlay controller instance.
     * @param volumeDistance     Unit distance for volume scrolling, in dp.
     * @param brightnessDistance Unit distance for brightness scrolling, in dp; higher = more precise.
     * @param speedDistance      Unit distance for speed scrolling, in dp; higher = more precise.
     * @param speedStepInt       Playback speed change per tick, expressed as an integer multiplied by 100 (e.g. 5 = 0.05x).
     * @param enableSpeedGesture Whether the playback speed swipe gesture is enabled.
     */
    public VolumeAndBrightnessScroller(
            Context context,
            @Nullable AudioVolumeController volumeController,
            @Nullable ScreenBrightnessController screenController,
            SwipeControlsOverlay overlayController,
            int volumeDistance,
            int brightnessDistance,
            int speedDistance,
            int speedStepInt,
            boolean enableSpeedGesture
    ) {
        volumeScroller = new ScrollDistanceHelper(
                SwipeControlsUtils.applyDimension(context, TypedValue.COMPLEX_UNIT_DIP, volumeDistance),
                direction -> {
                    if (volumeController != null) {
                        volumeController.adjustVolumeBySteps(direction);
                        overlayController.onVolumeChanged(
                                volumeController.getSteppedVolume(),
                                volumeController.getSteppedMaxVolume(),
                                volumeController.getBoostStep()
                        );
                    }
                }
        );

        brightnessScroller = new ScrollDistanceHelper(
                SwipeControlsUtils.applyDimension(context, TypedValue.COMPLEX_UNIT_DIP, brightnessDistance),
                direction -> {
                    if (screenController != null) {
                        final double screenBrightness = screenController.getScreenBrightness();
                        final boolean shouldAdjustBrightness;
                        if (screenController.host.getConfig().shouldLowestValueEnableAutoBrightness()) {
                            shouldAdjustBrightness = screenBrightness > 0 || direction > 0;
                        } else {
                            shouldAdjustBrightness = screenBrightness >= 0 || direction >= 0;
                        }

                        if (shouldAdjustBrightness) {
                            screenController.setScreenBrightness(screenBrightness + direction);
                        } else {
                            screenController.restoreDefaultBrightness();
                        }
                        overlayController.onBrightnessChanged(screenController.getScreenBrightness());
                    }
                }
        );

        speedScroller = new ScrollDistanceHelper(
                SwipeControlsUtils.applyDimension(context, TypedValue.COMPLEX_UNIT_DIP, speedDistance),
                direction -> {
                    if (!enableSpeedGesture) return;
                    // Integer math (x100) avoids float drift like 1.04999 instead of 1.05.
                    final int currentSpeedInt = Math.round(VideoInformation.getPlaybackSpeed() * 100);
                    final int newSpeedInt = Math.max(MINIMUM_SPEED_INT,
                            Math.min(MAXIMUM_SPEED_INT, currentSpeedInt + direction * speedStepInt));
                    final float newSpeed = newSpeedInt / 100f;
                    VideoInformation.changePlaybackSpeed(newSpeed);
                    overlayController.onSpeedChanged(newSpeed);
                }
        );
    }

    /**
     * Submits a scroll for volume adjustment.
     *
     * @param distance The scroll distance.
     */
    public void scrollVolume(double distance) {
        volumeScroller.add(distance);
    }

    /**
     * Submits a scroll for brightness adjustment.
     *
     * @param distance The scroll distance.
     */
    public void scrollBrightness(double distance) {
        brightnessScroller.add(distance);
    }

    /**
     * Submits a scroll for playback speed adjustment.
     *
     * @param distance The scroll distance.
     */
    public void scrollSpeed(double distance) {
        speedScroller.add(distance);
    }

    /**
     * Resets all scroll distances to zero.
     */
    public void resetScroller() {
        volumeScroller.reset();
        brightnessScroller.reset();
        speedScroller.reset();
    }
}
