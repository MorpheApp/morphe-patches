package app.morphe.extension.youtube.swipecontrols.controller.gesture.core

import android.content.Context
import android.util.TypedValue
import app.morphe.extension.youtube.patches.VideoInformation
import app.morphe.extension.youtube.swipecontrols.controller.AudioVolumeController
import app.morphe.extension.youtube.swipecontrols.controller.ScreenBrightnessController
import app.morphe.extension.youtube.swipecontrols.misc.ScrollDistanceHelper
import app.morphe.extension.youtube.swipecontrols.misc.SwipeControlsOverlay
import app.morphe.extension.youtube.swipecontrols.misc.applyDimension

/**
 * Describes a class that controls volume, brightness, and playback speed based on scrolling events.
 */
interface VolumeAndBrightnessScroller {
    /**
     * Submits a scroll for volume adjustment.
     *
     * @param distance The scroll distance.
     */
    fun scrollVolume(distance: Double)

    /**
     * Submits a scroll for brightness adjustment.
     *
     * @param distance The scroll distance.
     */
    fun scrollBrightness(distance: Double)

    /**
     * Submits a scroll for playback speed adjustment.
     *
     * @param distance The scroll distance.
     */
    fun scrollSpeed(distance: Double)

    /**
     * Resets all scroll distances to zero.
     */
    fun resetScroller()
}

/**
 * Handles scrolling of volume, brightness, and playback speed, adjusts them using the provided controllers and updates the overlay.
 *
 * @param context Context to create the scrollers in.
 * @param volumeController Volume controller instance. If null, volume control is disabled.
 * @param screenController Screen brightness controller instance. If null, brightness control is disabled.
 * @param overlayController Overlay controller instance.
 * @param volumeDistance Unit distance for volume scrolling, in dp.
 * @param brightnessDistance Unit distance for brightness scrolling, in dp; higher = more precise.
 * @param volumeSwipeSensitivity How much volume will change per swipe.
 * @param speedDistance Unit distance for speed scrolling, in dp; higher = more precise.
 * @param enableSpeedGesture Whether the playback speed swipe gesture is enabled.
 */
class VolumeAndBrightnessScrollerImpl(
    context: Context,
    private val volumeController: AudioVolumeController?,
    private val screenController: ScreenBrightnessController?,
    private val overlayController: SwipeControlsOverlay,
    volumeDistance: Int = 10,
    brightnessDistance: Int = 1,
    private val volumeSwipeSensitivity: Int,
    speedDistance: Int = 10,
    private val enableSpeedGesture: Boolean = false,
) : VolumeAndBrightnessScroller {

    // region volume
    private val volumeScroller =
        ScrollDistanceHelper(
            volumeDistance.applyDimension(
                context,
                TypedValue.COMPLEX_UNIT_DIP,
            ),
        ) { _, _, direction ->
            volumeController?.run {
                volume += direction * volumeSwipeSensitivity
                overlayController.onVolumeChanged(volume, maxVolume)
            }
        }

    override fun scrollVolume(distance: Double) = volumeScroller.add(distance)
    //endregion

    //region brightness
    private val brightnessScroller =
        ScrollDistanceHelper(
            brightnessDistance.applyDimension(
                context,
                TypedValue.COMPLEX_UNIT_DIP,
            ),
        ) { _, _, direction ->
            screenController?.run {
                val shouldAdjustBrightness = if (host.config.shouldLowestValueEnableAutoBrightness) {
                    screenBrightness > 0 || direction > 0
                } else {
                    screenBrightness >= 0 || direction >= 0
                }

                if (shouldAdjustBrightness) {
                    screenBrightness += direction
                } else {
                    restoreDefaultBrightness()
                }
                overlayController.onBrightnessChanged(screenBrightness)
            }
        }

    override fun scrollBrightness(distance: Double) = brightnessScroller.add(distance)
    //endregion

    //region speed
    private val speedScroller =
        ScrollDistanceHelper(
            speedDistance.applyDimension(
                context,
                TypedValue.COMPLEX_UNIT_DIP,
            ),
        ) { _, _, direction ->
            if (!enableSpeedGesture) return@ScrollDistanceHelper
            val currentSpeed = VideoInformation.getPlaybackSpeed()
            val newSpeed = maxOf(0.25f, minOf(8.0f, currentSpeed + direction * 0.25f))
            VideoInformation.overridePlaybackSpeed(newSpeed)
            overlayController.onSpeedChanged(newSpeed)
        }

    override fun scrollSpeed(distance: Double) = speedScroller.add(distance)
    //endregion

    override fun resetScroller() {
        volumeScroller.reset()
        brightnessScroller.reset()
        speedScroller.reset()
    }
}
