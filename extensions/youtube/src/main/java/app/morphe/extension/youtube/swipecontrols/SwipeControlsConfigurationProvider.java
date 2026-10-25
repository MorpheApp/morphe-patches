/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.swipecontrols;

import static app.morphe.extension.shared.StringRef.str;

import android.graphics.Color;
import android.view.MotionEvent;

import java.util.List;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.shared.settings.StringSetting;
import app.morphe.extension.shared.settings.preference.SeekBarPreference;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.PlayerType;
import app.morphe.extension.youtube.swipecontrols.controller.gesture.ClassicSwipeController;
import app.morphe.extension.youtube.swipecontrols.controller.gesture.PressToSwipeController;
import app.morphe.extension.youtube.swipecontrols.controller.gesture.core.BaseGestureController;

/**
 * Provides configuration settings for the swipe controls in the YouTube player.
 * Manages the action of each zone, overlay appearance, and behavior preferences.
 */
public final class SwipeControlsConfigurationProvider {
    //region zone actions
    /**
     * The action performed by a swipe zone.
     */
    public enum SwipeZoneAction {
        OFF,
        VOLUME,
        BRIGHTNESS,
        SPEED
    }

    /**
     * Availability based on any zone having an action assigned.
     */
    public static class AnySwipeZoneAvailability implements Setting.Availability {
        @Override
        public boolean isAvailable() {
            return Settings.SWIPE_LEFT_ZONE.get() != SwipeZoneAction.OFF
                    || Settings.SWIPE_RIGHT_ZONE.get() != SwipeZoneAction.OFF
                    || Settings.SWIPE_TOP_ZONE.get() != SwipeZoneAction.OFF;
        }

        @Override
        public List<Setting<?>> getParentSettings() {
            return List.of(Settings.SWIPE_LEFT_ZONE, Settings.SWIPE_RIGHT_ZONE, Settings.SWIPE_TOP_ZONE);
        }
    }

    /**
     * Availability based on either side zone having an action assigned.
     */
    public static class SideSwipeZonesAvailability implements Setting.Availability {
        @Override
        public boolean isAvailable() {
            return Settings.SWIPE_LEFT_ZONE.get() != SwipeZoneAction.OFF
                    || Settings.SWIPE_RIGHT_ZONE.get() != SwipeZoneAction.OFF;
        }

        @Override
        public List<Setting<?>> getParentSettings() {
            return List.of(Settings.SWIPE_LEFT_ZONE, Settings.SWIPE_RIGHT_ZONE);
        }
    }

    /**
     * Availability based on the top zone having an action assigned.
     */
    public static class TopSwipeZoneAvailability implements Setting.Availability {
        @Override
        public boolean isAvailable() {
            return Settings.SWIPE_TOP_ZONE.get() != SwipeZoneAction.OFF;
        }

        @Override
        public List<Setting<?>> getParentSettings() {
            return List.of(Settings.SWIPE_TOP_ZONE);
        }
    }

    /**
     * Availability based on any zone being assigned the given action.
     */
    public static class SwipeActionAvailability implements Setting.Availability {
        private final SwipeZoneAction action;

        public SwipeActionAvailability(SwipeZoneAction action) {
            this.action = action;
        }

        @Override
        public boolean isAvailable() {
            return Settings.SWIPE_LEFT_ZONE.get() == action
                    || Settings.SWIPE_RIGHT_ZONE.get() == action
                    || Settings.SWIPE_TOP_ZONE.get() == action;
        }

        @Override
        public List<Setting<?>> getParentSettings() {
            return List.of(Settings.SWIPE_LEFT_ZONE, Settings.SWIPE_RIGHT_ZONE, Settings.SWIPE_TOP_ZONE);
        }
    }
    //endregion

    //region swipe enable
    /**
     * Indicates whether swipe controls are enabled globally.
     * Returns true if either volume or brightness controls are enabled and the video is in fullscreen or multi-window mode.
     */
    public boolean isSwipeControlsEnabled() {
        return (isVolumeControlEnabled() || isBrightnessControlEnabled() || isSpeedControlEnabled())
                && (isFullscreenOrMultiWindowVideo() || isVideoSliding());
    }

    public SwipeZoneAction getLeftZoneAction() {
        return Settings.SWIPE_LEFT_ZONE.get();
    }

    public SwipeZoneAction getRightZoneAction() {
        return Settings.SWIPE_RIGHT_ZONE.get();
    }

    public SwipeZoneAction getTopZoneAction() {
        return Settings.SWIPE_TOP_ZONE.get();
    }

    /**
     * Indicates whether any zone is assigned the given action.
     */
    private boolean isActionAssigned(SwipeZoneAction action) {
        return getLeftZoneAction() == action || getRightZoneAction() == action || getTopZoneAction() == action;
    }

    /**
     * Indicates whether swipe controls for adjusting volume are enabled.
     */
    public boolean isVolumeControlEnabled() {
        return isActionAssigned(SwipeZoneAction.VOLUME);
    }

    /**
     * Indicates whether swipe controls for adjusting brightness are enabled.
     */
    public boolean isBrightnessControlEnabled() {
        return isActionAssigned(SwipeZoneAction.BRIGHTNESS);
    }

    /**
     * Checks if the video player is currently in fullscreen mode.
     */
    public boolean isFullscreenVideo() {
        return PlayerType.getCurrent() == PlayerType.WATCH_WHILE_FULLSCREEN;
    }

    /**
     * Checks if the video player is currently in split screen / multi-window mode.
     */
    public boolean isMultiWindowVideo() {
        SwipeControlsHostActivity host = SwipeControlsHostActivity.getCurrentHost().get();
        if (host == null || !host.isInSplitScreenMode()) {
            return false;
        }
        PlayerType current = PlayerType.getCurrent();
        return current == PlayerType.WATCH_WHILE_FULLSCREEN || current == PlayerType.WATCH_WHILE_MAXIMIZED;
    }

    /**
     * Checks if the video player is in fullscreen or multi-window mode.
     */
    public boolean isFullscreenOrMultiWindowVideo() {
        return isFullscreenVideo() || isMultiWindowVideo();
    }

    /**
     * Checks if the video player is currently in sliding mode.
     * <p>
     * The swipe control patch hooks functions of MainActivity (top-level activity) to detect {@link MotionEvent}.
     * Although the player is already in the fullscreen, a {@link MotionEvent} is detected before the {@link PlayerType} is updated,
     * so the current {@link PlayerType} may be {@link PlayerType#WATCH_WHILE_SLIDING_MAXIMIZED_FULLSCREEN}.
     * <p>
     * In this case, {@link BaseGestureController#submitTouchEvent} cancels the MotionEvent,
     * but sometimes the canceled {@link MotionEvent} triggers the tap and hold playback speed.
     * See: https://github.com/MorpheApp/morphe-patches/issues/658.
     * <p>
     * To resolve this concurrency issue, pass the {@link MotionEvent} even when the player type is
     * {@link PlayerType#WATCH_WHILE_SLIDING_MAXIMIZED_FULLSCREEN}, and finally validate the swipe gesture
     * in {@link ClassicSwipeController} and {@link PressToSwipeController} onSwipe().
     */
    public boolean isVideoSliding() {
        return PlayerType.getCurrent() == PlayerType.WATCH_WHILE_SLIDING_MAXIMIZED_FULLSCREEN;
    }
    //endregion

    //region keys enable
    /**
     * Indicates whether volume key controls should be overridden by swipe controls.
     * Returns true if volume controls are enabled and the video is in fullscreen or multi-window mode.
     */
    public boolean shouldOverwriteVolumeKeyControls() {
        return isVolumeControlEnabled() && isFullscreenOrMultiWindowVideo();
    }
    //endregion

    //region gesture adjustments
    /**
     * Indicates whether swipe gestures should be ignored while the native lock screen is engaged.
     */
    public boolean shouldIgnoreSwipesWhenLocked() {
        return Settings.SWIPE_IGNORE_WHEN_LOCKED.get();
    }

    /**
     * Indicates whether press-to-swipe mode is enabled, requiring a press before swiping to activate controls.
     */
    private final boolean shouldEnablePressToSwipe = Settings.SWIPE_PRESS_TO_ENGAGE.get();

    public boolean shouldEnablePressToSwipe() {
        return shouldEnablePressToSwipe;
    }

    /**
     * The threshold for detecting swipe gestures, in pixels.
     * Loaded once to ensure consistent behavior during rapid scroll events.
     */
    private final int swipeMagnitudeThreshold = Settings.SWIPE_MAGNITUDE_THRESHOLD.get();

    public int getSwipeMagnitudeThreshold() {
        return swipeMagnitudeThreshold;
    }

    /**
     * The swipe distance of a single volume step, in dp.
     * Resets to default if set to 0, as it would disable swiping.
     */
    public int getVolumeSwipeDistance() {
        final int distance = Settings.SWIPE_VOLUME_DISTANCE.get();

        if (distance < 1) {
            return Settings.SWIPE_VOLUME_DISTANCE.resetToDefault();
        }

        return distance;
    }

    /**
     * Number of steps the volume range is divided into.
     * Devices that expose a fine-grained volume stream (such as 150 indices) otherwise
     * adjust the volume by a tenth of what the device volume UI does.
     */
    @SuppressWarnings("unused")
    public enum SwipeVolumeSteps {
        DEVICE_DEFAULT(0),
        STEPS_5(5),
        STEPS_10(10),
        STEPS_15(15),
        STEPS_20(20),
        STEPS_30(30),
        STEPS_50(50);

        private final int steps;

        SwipeVolumeSteps(int steps) {
            this.steps = steps;
        }

        public int getSteps() {
            return steps;
        }
    }

    /**
     * The number of volume steps to use, or 0 to use the raw steps of the device volume stream.
     */
    public int getVolumeStepCount() {
        return Settings.SWIPE_VOLUME_STEPS.get().getSteps();
    }

    /**
     * The volume change of a single step, in stream index units.
     *
     * @param maxVolume The number of indices the device volume stream has.
     * @param stepCount The number of steps to split the stream into, or 0 for the raw indices.
     */
    public static int volumeStepSize(int maxVolume, int stepCount) {
        return stepCount < 1 ? 1 : Math.max(1, maxVolume / stepCount);
    }

    /**
     * The sensitivity of brightness swipe gestures, determining how much brightness changes per swipe.
     * Resets to default if set to 0, as it would disable swiping.
     */
    public int getBrightnessSwipeSensitivity() {
        final int sensitivity = Settings.SWIPE_BRIGHTNESS_SENSITIVITY.get();

        if (sensitivity < 1) {
            return Settings.SWIPE_BRIGHTNESS_SENSITIVITY.resetToDefault();
        }

        return sensitivity;
    }

    /**
     * Indicates whether the swipe gesture for playback speed control is enabled in any zone.
     */
    public boolean isSpeedControlEnabled() {
        return isActionAssigned(SwipeZoneAction.SPEED);
    }

    /**
     * The sensitivity of speed swipe gestures, controlling how much physical movement is needed per step.
     * Resets to default if below 1 to guard against direct SharedPreferences manipulation.
     */
    public int getSpeedSwipeSensitivity() {
        final int sensitivity = Settings.SWIPE_SPEED_SENSITIVITY.get();

        if (sensitivity < 1) {
            return Settings.SWIPE_SPEED_SENSITIVITY.resetToDefault();
        }

        return sensitivity;
    }

    /**
     * Playback speed change per tick, expressed as an integer multiplied by 100 (e.g. 5 = 0.05x).
     */
    public int getSpeedStepInt() {
        return Settings.SWIPE_SPEED_STEP.get().getStepInt();
    }
    //endregion

    //region overlay adjustments
    /**
     * The background color used for the filled portion of the progress bar in the overlay.
     */
    public static final int OVERLAY_FILL_BACKGROUND_COLOR = 0x80D3D3D3;

    /**
     * The color used for text and icons in the overlay.
     */
    public static final int OVERLAY_TEXT_COLOR = Color.WHITE;

    /**
     * Indicates whether haptic feedback should be enabled for swipe control interactions.
     */
    private final boolean shouldEnableHapticFeedback = Settings.SWIPE_HAPTIC_FEEDBACK.get();

    public boolean shouldEnableHapticFeedback() {
        return shouldEnableHapticFeedback;
    }

    /**
     * The duration in milliseconds that the overlay should remain visible after a change.
     */
    private final long overlayShowTimeoutMillis = Settings.SWIPE_OVERLAY_TIMEOUT.get();

    public long getOverlayShowTimeoutMillis() {
        return overlayShowTimeoutMillis;
    }

    /**
     * The background opacity of the overlay, converted from a percentage (0-100) to an alpha value (0-255).
     */
    public int getOverlayBackgroundOpacity() {
        final int opacity = SeekBarPreference.clampToRange(Settings.SWIPE_OVERLAY_OPACITY) * 255 / 100;
        return Color.argb(opacity, 0, 0, 0);
    }

    /**
     * The color of the progress bar in the overlay for brightness.
     * Resets to default and shows a toast if the color string is invalid or empty.
     */
    public int getOverlayBrightnessProgressColor() {
        return getSettingColor(Settings.SWIPE_OVERLAY_BRIGHTNESS_COLOR);
    }

    /**
     * The color of the progress bar in the overlay for volume.
     * Resets to default and shows a toast if the color string is invalid or empty.
     */
    public int getOverlayVolumeProgressColor() {
        return getSettingColor(Settings.SWIPE_OVERLAY_VOLUME_COLOR);
    }

    /**
     * The color of the progress indicator in the overlay for playback speed.
     */
    public int getOverlaySpeedProgressColor() {
        return getSettingColor(Settings.SWIPE_OVERLAY_SPEED_COLOR);
    }

    private static int getSettingColor(StringSetting setting) {
        try {
            return Color.parseColor(setting.get());
        } catch (IllegalArgumentException ex) {
            // This code should never be reached.
            // Color picker rejects and will not save bad colors to a setting.
            // If a user imports bad data, the color picker preference resets the
            // bad color before this method can be called.
            Logger.printDebug(() -> "Could not parse color: " + setting, ex);
            Utils.showToastLong(str("morphe_settings_color_invalid"));
            setting.resetToDefault();
            return getSettingColor(setting); // Recursively return.
        }
    }

    /**
     * The text size in the overlay, in density-independent pixels (dp).
     */
    public int getOverlayTextSize() {
        return SeekBarPreference.clampToRange(Settings.SWIPE_OVERLAY_TEXT_SIZE);
    }

    /**
     * Defines the style of the swipe controls overlay, determining its layout and appearance.
     */
    @SuppressWarnings("unused")
    public enum SwipeOverlayStyle {
        /**
         * A full horizontal progress bar with detailed indicators.
         */
        HORIZONTAL(false, false, false, false),

        /**
         * A minimal horizontal progress bar positioned at the top.
         */
        HORIZONTAL_MINIMAL_TOP(true, false, false, false),

        /**
         * A minimal horizontal progress bar centered vertically.
         */
        HORIZONTAL_MINIMAL_CENTER(true, true, false, false),

        /**
         * A full circular progress bar with detailed indicators.
         */
        CIRCULAR(false, false, true, false),

        /**
         * A minimal circular progress bar.
         */
        CIRCULAR_MINIMAL(true, false, true, false),

        /**
         * A full vertical progress bar with detailed indicators.
         */
        VERTICAL(false, false, false, true),

        /**
         * A minimal vertical progress bar.
         */
        VERTICAL_MINIMAL(true, false, false, true);

        private final boolean isMinimal;
        private final boolean isHorizontalMinimalCenter;
        private final boolean isCircular;
        private final boolean isVertical;

        /**
         * @param isMinimal                 Indicates whether the style is minimalistic, omitting detailed progress indicators.
         * @param isHorizontalMinimalCenter Indicates whether the style is a minimal horizontal bar centered vertically.
         * @param isCircular                Indicates whether the style uses a circular progress bar.
         * @param isVertical                Indicates whether the style uses a vertical progress bar.
         */
        SwipeOverlayStyle(boolean isMinimal, boolean isHorizontalMinimalCenter, boolean isCircular, boolean isVertical) {
            this.isMinimal = isMinimal;
            this.isHorizontalMinimalCenter = isHorizontalMinimalCenter;
            this.isCircular = isCircular;
            this.isVertical = isVertical;
        }

        public boolean isMinimal() {
            return isMinimal;
        }

        public boolean isHorizontalMinimalCenter() {
            return isHorizontalMinimalCenter;
        }

        public boolean isCircular() {
            return isCircular;
        }

        public boolean isVertical() {
            return isVertical;
        }
    }

    /**
     * The current style of the overlay, determining its layout and appearance.
     */
    private final SwipeOverlayStyle overlayStyle = Settings.SWIPE_OVERLAY_STYLE.get();

    public SwipeOverlayStyle getOverlayStyle() {
        return overlayStyle;
    }

    /**
     * Playback speed change per tick, expressed as an integer multiplied by 100 (e.g. 5 = 0.05x).
     */
    @SuppressWarnings("unused")
    public enum SwipeSpeedStep {
        STEP_005(5),
        STEP_010(10),
        STEP_025(25);

        private final int stepInt;

        SwipeSpeedStep(int stepInt) {
            this.stepInt = stepInt;
        }

        public int getStepInt() {
            return stepInt;
        }
    }
    //endregion

    //region behavior
    /**
     * Indicates whether the brightness level should be saved and restored when entering or exiting fullscreen mode.
     */
    private final boolean shouldSaveAndRestoreBrightness = Settings.SWIPE_SAVE_AND_RESTORE_BRIGHTNESS.get();

    public boolean shouldSaveAndRestoreBrightness() {
        return shouldSaveAndRestoreBrightness;
    }

    /**
     * Indicates whether auto-brightness should be enabled when the brightness gesture reaches its lowest value.
     */
    private final boolean shouldLowestValueEnableAutoBrightness = Settings.SWIPE_LOWEST_VALUE_ENABLE_AUTO_BRIGHTNESS.get();

    public boolean shouldLowestValueEnableAutoBrightness() {
        return shouldLowestValueEnableAutoBrightness;
    }

    /**
     * The saved brightness value for the swipe gesture, used to restore brightness in fullscreen mode.
     */
    public float getSavedScreenBrightnessValue() {
        return Settings.SWIPE_BRIGHTNESS_VALUE.get();
    }

    public void setSavedScreenBrightnessValue(float value) {
        Settings.SWIPE_BRIGHTNESS_VALUE.save(value);
    }
    //endregion
}
