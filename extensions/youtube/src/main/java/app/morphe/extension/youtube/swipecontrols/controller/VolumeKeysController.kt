/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

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
        // Without a volume controller the device has to handle the keys, or they would do nothing.
        if (!controller.config.overwriteVolumeKeyControls || controller.audio == null) {
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
                adjustVolumeBySteps(if (volumeUp) 1 else -1)
                controller.overlay.onVolumeChanged(steppedVolume, steppedMaxVolume)
            }
        }

        return true
    }
}
