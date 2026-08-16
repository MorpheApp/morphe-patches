/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.swipecontrols

import android.graphics.Color
import android.view.MotionEvent
import app.morphe.extension.shared.Logger
import app.morphe.extension.shared.StringRef.str
import app.morphe.extension.shared.Utils
import app.morphe.extension.shared.settings.Setting
import app.morphe.extension.shared.settings.StringSetting
import app.morphe.extension.youtube.settings.Settings
import app.morphe.extension.youtube.shared.PlayerType
import app.morphe.extension.youtube.swipecontrols.controller.gesture.ClassicSwipeController
import app.morphe.extension.youtube.swipecontrols.controller.gesture.PressToSwipeController
import app.morphe.extension.youtube.swipecontrols.controller.gesture.core.BaseGestureController

/**
 * Provides configuration settings for the swipe controls in the YouTube player.
 * Manages the action of each zone, overlay appearance, and behavior preferences.
 */
class SwipeControlsConfigurationProvider {
    //region zone actions
    /**
     * The action performed by a swipe zone.
     */
    enum class SwipeZoneAction {
        OFF,
        VOLUME,
        BRIGHTNESS,
        SPEED,
    }

    /**
     * Availability based on any zone having an action assigned.
     */
    class AnySwipeZoneAvailability : Setting.Availability {
        override fun isAvailable() =
            Settings.SWIPE_LEFT_ZONE.get() != SwipeZoneAction.OFF ||
                Settings.SWIPE_RIGHT_ZONE.get() != SwipeZoneAction.OFF ||
                Settings.SWIPE_TOP_ZONE.get() != SwipeZoneAction.OFF

        override fun getParentSettings() =
            listOf<Setting<*>>(Settings.SWIPE_LEFT_ZONE, Settings.SWIPE_RIGHT_ZONE, Settings.SWIPE_TOP_ZONE)
    }

    /**
     * Availability based on either side zone having an action assigned.
     */
    class SideSwipeZonesAvailability : Setting.Availability {
        override fun isAvailable() =
            Settings.SWIPE_LEFT_ZONE.get() != SwipeZoneAction.OFF ||
                Settings.SWIPE_RIGHT_ZONE.get() != SwipeZoneAction.OFF

        override fun getParentSettings() =
            listOf<Setting<*>>(Settings.SWIPE_LEFT_ZONE, Settings.SWIPE_RIGHT_ZONE)
    }

    /**
     * Availability based on the top zone having an action assigned.
     */
    class TopSwipeZoneAvailability : Setting.Availability {
        override fun isAvailable() = Settings.SWIPE_TOP_ZONE.get() != SwipeZoneAction.OFF

        override fun getParentSettings() = listOf<Setting<*>>(Settings.SWIPE_TOP_ZONE)
    }

    /**
     * Availability based on any zone being assigned the given action.
     */
    class SwipeActionAvailability(private val action: SwipeZoneAction) : Setting.Availability {
        override fun isAvailable() =
            Settings.SWIPE_LEFT_ZONE.get() == action ||
                Settings.SWIPE_RIGHT_ZONE.get() == action ||
                Settings.SWIPE_TOP_ZONE.get() == action

        override fun getParentSettings() =
            listOf<Setting<*>>(Settings.SWIPE_LEFT_ZONE, Settings.SWIPE_RIGHT_ZONE, Settings.SWIPE_TOP_ZONE)
    }
    //endregion

    //region swipe enable
    /**
     * Indicates whether swipe controls are enabled globally.
     * Returns true if either volume or brightness controls are enabled and the video is in fullscreen mode.
     */
    val enableSwipeControls: Boolean
        get() = (enableVolumeControls || enableBrightnessControl || enableSpeedGestureControl) && (isFullscreenVideo || isVideoSliding)

    val leftZoneAction: SwipeZoneAction
        get() = Settings.SWIPE_LEFT_ZONE.get()

    val rightZoneAction: SwipeZoneAction
        get() = Settings.SWIPE_RIGHT_ZONE.get()

    val topZoneAction: SwipeZoneAction
        get() = Settings.SWIPE_TOP_ZONE.get()

    /**
     * Indicates whether any zone is assigned the given action.
     */
    private fun isActionAssigned(action: SwipeZoneAction) =
        leftZoneAction == action || rightZoneAction == action || topZoneAction == action

    /**
     * Indicates whether swipe controls for adjusting volume are enabled.
     */
    val enableVolumeControls: Boolean
        get() = isActionAssigned(SwipeZoneAction.VOLUME)

    /**
     * Indicates whether swipe controls for adjusting brightness are enabled.
     */
    val enableBrightnessControl: Boolean
        get() = isActionAssigned(SwipeZoneAction.BRIGHTNESS)

    /**
     * Checks if the video player is currently in fullscreen mode.
     */
    val isFullscreenVideo: Boolean
        get() = PlayerType.current == PlayerType.WATCH_WHILE_FULLSCREEN

    /**
     * Checks if the video player is currently in sliding mode.
     *
     * The swipe control patch hooks functions of MainActivity (top-level activity) to detect [MotionEvent].
     * Although the player is already in the fullscreen, a [MotionEvent] is detected before the [PlayerType] is updated,
     * so the current [PlayerType] may be [PlayerType.WATCH_WHILE_SLIDING_MAXIMIZED_FULLSCREEN].
     *
     * In this case, [BaseGestureController.submitTouchEvent] cancels the MotionEvent,
     * but sometimes the canceled [MotionEvent] triggers the tap and hold playback speed.
     * See: https://github.com/MorpheApp/morphe-patches/issues/658.
     *
     * To resolve this concurrency issue, pass the [MotionEvent] even when the player type is [PlayerType.WATCH_WHILE_SLIDING_MAXIMIZED_FULLSCREEN],
     * and finally validate the swipe gesture in [ClassicSwipeController.onSwipe] and [PressToSwipeController.onSwipe].
     */
    val isVideoSliding: Boolean
        get() = PlayerType.current == PlayerType.WATCH_WHILE_SLIDING_MAXIMIZED_FULLSCREEN
    //endregion

    //region keys enable
    /**
     * Indicates whether volume key controls should be overridden by swipe controls.
     * Returns true if volume controls are enabled and the video is in fullscreen mode.
     */
    val overwriteVolumeKeyControls: Boolean
        get() = enableVolumeControls && isFullscreenVideo
    //endregion

    //region gesture adjustments
    /**
     * Indicates whether swipe gestures should be ignored while the native lock screen is engaged.
     */
    val shouldIgnoreSwipesWhenLocked: Boolean
        get() = Settings.SWIPE_IGNORE_WHEN_LOCKED.get()

    /**
     * Indicates whether press-to-swipe mode is enabled, requiring a press before swiping to activate controls.
     */
    val shouldEnablePressToSwipe = Settings.SWIPE_PRESS_TO_ENGAGE.get()

    /**
     * The threshold for detecting swipe gestures, in pixels.
     * Loaded once to ensure consistent behavior during rapid scroll events.
     */
    val swipeMagnitudeThreshold = Settings.SWIPE_MAGNITUDE_THRESHOLD.get()

    /**
     * The sensitivity of volume swipe gestures, determining how much volume changes per swipe.
     * Resets to default if set to 0, as it would disable swiping.
     */
    val volumeSwipeSensitivity: Int
        get() {
            val sensitivity = Settings.SWIPE_VOLUME_SENSITIVITY.get()

            if (sensitivity < 1) {
                return Settings.SWIPE_VOLUME_SENSITIVITY.resetToDefault()
            }

            return sensitivity
        }

    /**
     * The sensitivity of brightness swipe gestures, determining how much brightness changes per swipe.
     * Resets to default if set to 0, as it would disable swiping.
     */
    val brightnessSwipeSensitivity: Int
        get() {
            val sensitivity = Settings.SWIPE_BRIGHTNESS_SENSITIVITY.get()

            if (sensitivity < 1) {
                return Settings.SWIPE_BRIGHTNESS_SENSITIVITY.resetToDefault()
            }

            return sensitivity
        }

    /**
     * Indicates whether the swipe gesture for playback speed control is enabled in any zone.
     */
    val enableSpeedGestureControl: Boolean
        get() = isActionAssigned(SwipeZoneAction.SPEED)

    /**
     * The sensitivity of speed swipe gestures, controlling how much physical movement is needed per step.
     * Resets to default if below 1 to guard against direct SharedPreferences manipulation.
     */
    val speedSwipeSensitivity: Int
        get() {
            val sensitivity = Settings.SWIPE_SPEED_SENSITIVITY.get()

            if (sensitivity < 1) {
                return Settings.SWIPE_SPEED_SENSITIVITY.resetToDefault()
            }

            return sensitivity
        }

    /**
     * Playback speed change per tick, expressed as an integer multiplied by 100 (e.g. 5 = 0.05x).
     */
    val speedStepInt: Int
        get() = Settings.SWIPE_SPEED_STEP.get().stepInt

    //endregion

    //region overlay adjustments
    /**
     * Indicates whether haptic feedback should be enabled for swipe control interactions.
     */
    val shouldEnableHapticFeedback = Settings.SWIPE_HAPTIC_FEEDBACK.get()

    /**
     * The duration in milliseconds that the overlay should remain visible after a change.
     */
    val overlayShowTimeoutMillis = Settings.SWIPE_OVERLAY_TIMEOUT.get()

    /**
     * The background opacity of the overlay, converted from a percentage (0-100) to an alpha value (0-255).
     * Resets to default and shows a toast if the value is out of range.
     */
    val overlayBackgroundOpacity: Int
        get() {
            var opacity = Settings.SWIPE_OVERLAY_OPACITY.get()

            if (opacity !in 0..100) {
                Utils.showToastLong(str("morphe_swipe_overlay_background_opacity_invalid_toast"))
                opacity = Settings.SWIPE_OVERLAY_OPACITY.resetToDefault()
            }

            opacity = opacity * 255 / 100
            return Color.argb(opacity, 0, 0, 0)
        }

    /**
     * The color of the progress bar in the overlay for brightness.
     * Resets to default and shows a toast if the color string is invalid or empty.
     */
    val overlayBrightnessProgressColor: Int
        get() = getSettingColor(Settings.SWIPE_OVERLAY_BRIGHTNESS_COLOR)

    /**
     * The color of the progress bar in the overlay for volume.
     * Resets to default and shows a toast if the color string is invalid or empty.
     */
    val overlayVolumeProgressColor: Int
        get() = getSettingColor(Settings.SWIPE_OVERLAY_VOLUME_COLOR)

    private fun getSettingColor(setting: StringSetting): Int {
        return try {
            Color.parseColor(setting.get())
        } catch (ex: IllegalArgumentException) {
            // This code should never be reached.
            // Color picker rejects and will not save bad colors to a setting.
            // If a user imports bad data, the color picker preference resets the
            // bad color before this method can be called.
            Logger.printDebug({ "Could not parse color: $setting" }, ex)
            Utils.showToastLong(str("morphe_settings_color_invalid"))
            setting.resetToDefault()
            return getSettingColor(setting) // Recursively return.
        }
    }

    /**
     * The color of the progress indicator in the overlay for playback speed.
     */
    val overlaySpeedProgressColor: Int
        get() = getSettingColor(Settings.SWIPE_OVERLAY_SPEED_COLOR)

    /**
     * The background color used for the filled portion of the progress bar in the overlay.
     */
    val overlayFillBackgroundPaint = 0x80D3D3D3.toInt()

    /**
     * The color used for text and icons in the overlay.
     */
    val overlayTextColor = Color.WHITE

    /**
     * The text size in the overlay, in density-independent pixels (dp).
     * Must be between 1 and 30 dp; resets to default and shows a toast if invalid.
     */
    val overlayTextSize: Int
        get() {
            val size = Settings.SWIPE_OVERLAY_TEXT_SIZE.get()
            if (size !in 1..30) {
                Utils.showToastLong(str("morphe_swipe_text_overlay_size_invalid_toast"))
                return Settings.SWIPE_OVERLAY_TEXT_SIZE.resetToDefault()
            }
            return size
        }

    /**
     * Defines the style of the swipe controls overlay, determining its layout and appearance.
     *
     * @property isMinimal Indicates whether the style is minimalistic, omitting detailed progress indicators.
     * @property isHorizontalMinimalCenter Indicates whether the style is a minimal horizontal bar centered vertically.
     * @property isCircular Indicates whether the style uses a circular progress bar.
     * @property isVertical Indicates whether the style uses a vertical progress bar.
     */
    @Suppress("unused")
    enum class SwipeOverlayStyle(
        val isMinimal: Boolean = false,
        val isHorizontalMinimalCenter: Boolean = false,
        val isCircular: Boolean = false,
        val isVertical: Boolean = false
    ) {
        /**
         * A full horizontal progress bar with detailed indicators.
         */
        HORIZONTAL,

        /**
         * A minimal horizontal progress bar positioned at the top.
         */
        HORIZONTAL_MINIMAL_TOP(isMinimal = true),

        /**
         * A minimal horizontal progress bar centered vertically.
         */
        HORIZONTAL_MINIMAL_CENTER(isMinimal = true, isHorizontalMinimalCenter = true),

        /**
         * A full circular progress bar with detailed indicators.
         */
        CIRCULAR(isCircular = true),

        /**
         * A minimal circular progress bar.
         */
        CIRCULAR_MINIMAL(isMinimal = true, isCircular = true),

        /**
         * A full vertical progress bar with detailed indicators.
         */
        VERTICAL(isVertical = true),

        /**
         * A minimal vertical progress bar.
         */
        VERTICAL_MINIMAL(isMinimal = true, isVertical = true)
    }

    /**
     * The current style of the overlay, determining its layout and appearance.
     */
    val overlayStyle = Settings.SWIPE_OVERLAY_STYLE.get()

    /**
     * Playback speed change per tick, expressed as an integer multiplied by 100 (e.g. 5 = 0.05x).
     */
    @Suppress("unused")
    enum class SwipeSpeedStep(val stepInt: Int) {
        STEP_005(5),
        STEP_010(10),
        STEP_025(25),
    }
    //endregion

    //region behavior
    /**
     * Indicates whether the brightness level should be saved and restored when entering or exiting fullscreen mode.
     */
    val shouldSaveAndRestoreBrightness = Settings.SWIPE_SAVE_AND_RESTORE_BRIGHTNESS.get()

    /**
     * Indicates whether auto-brightness should be enabled when the brightness gesture reaches its lowest value.
     */
    val shouldLowestValueEnableAutoBrightness = Settings.SWIPE_LOWEST_VALUE_ENABLE_AUTO_BRIGHTNESS.get()

    /**
     * The saved brightness value for the swipe gesture, used to restore brightness in fullscreen mode.
     */
    var savedScreenBrightnessValue: Float
        get() = Settings.SWIPE_BRIGHTNESS_VALUE.get()
        set(value) = Settings.SWIPE_BRIGHTNESS_VALUE.save(value)
    //endregion
}
