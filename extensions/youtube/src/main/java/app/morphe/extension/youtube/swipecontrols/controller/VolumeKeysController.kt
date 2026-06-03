package app.morphe.extension.youtube.swipecontrols.controller

import android.view.KeyEvent
import app.morphe.extension.youtube.swipecontrols.SwipeControlsHostActivity

/**
 * Controller for custom volume button behavior.
 *
 * @param controller Main controller instance.
 */
class VolumeKeysController(
    private val controller: SwipeControlsHostActivity,
) {
    /**
     * Key event handler.
     *
     * @param event The key event.
     * @return Whether to consume the event.
     */
    fun onKeyEvent(event: KeyEvent): Boolean {
        if (!controller.config.overwriteVolumeKeyControls) {
            return false
        }

        return when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN ->
                handleVolumeKeyEvent(event, false)
            KeyEvent.KEYCODE_VOLUME_UP ->
                handleVolumeKeyEvent(event, true)
            else -> false
        }
    }

    /**
     * Handles a volume up/down key event.
     *
     * @param event The key event.
     * @param volumeUp Whether the key pressed was the volume up key.
     * @return Whether to consume the event.
     */
    private fun handleVolumeKeyEvent(event: KeyEvent, volumeUp: Boolean): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            controller.audio?.apply {
                volume += controller.config.volumeSwipeSensitivity * if (volumeUp) 1 else -1
                controller.overlay.onVolumeChanged(volume, maxVolume)
            }
        }

        return true
    }
}
