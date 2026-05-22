package app.morphe.extension.youtube.swipecontrols.misc

/**
 * Interface for all overlays for swipe controls.
 */
interface SwipeControlsOverlay {
    /**
     * Called when the currently set volume level was changed.
     *
     * @param newVolume The new volume level.
     * @param maximumVolume The maximum volume index.
     */
    fun onVolumeChanged(newVolume: Int, maximumVolume: Int)

    /**
     * Called when the currently set screen brightness was changed.
     *
     * @param brightness The new screen brightness, in percent (range 0.0–100.0).
     */
    fun onBrightnessChanged(brightness: Double)

    /**
     * Called when the playback speed was changed via swipe gesture.
     *
     * @param speed The new playback speed multiplier.
     */
    fun onSpeedChanged(speed: Float)

    /**
     * Called when a new swipe session has started.
     */
    fun onEnterSwipeSession()
}
