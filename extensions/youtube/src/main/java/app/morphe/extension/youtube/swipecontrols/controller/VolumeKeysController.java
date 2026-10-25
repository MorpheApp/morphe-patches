/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.swipecontrols.controller;

import android.view.KeyEvent;

import app.morphe.extension.youtube.swipecontrols.SwipeControlsHostActivity;

/**
 * Controller for custom volume button behavior.
 */
public final class VolumeKeysController {

    /**
     * Main controller instance.
     */
    private final SwipeControlsHostActivity controller;

    /**
     * @param controller Main controller instance.
     */
    public VolumeKeysController(SwipeControlsHostActivity controller) {
        this.controller = controller;
    }

    /**
     * Key event handler.
     *
     * @param event The key event.
     * @return Whether to consume the event.
     */
    public boolean onKeyEvent(KeyEvent event) {
        // Without a volume controller the device has to handle the keys, or they would do nothing.
        if (!controller.getConfig().shouldOverwriteVolumeKeyControls() || controller.getAudio() == null) {
            return false;
        }

        return switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_VOLUME_DOWN -> handleVolumeKeyEvent(event, false);
            case KeyEvent.KEYCODE_VOLUME_UP -> handleVolumeKeyEvent(event, true);
            default -> false;
        };
    }

    /**
     * Handles a volume up/down key event.
     *
     * @param event    The key event.
     * @param volumeUp Whether the key pressed was the volume up key.
     * @return Whether to consume the event.
     */
    private boolean handleVolumeKeyEvent(KeyEvent event, boolean volumeUp) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            AudioVolumeController audio = controller.getAudio();
            if (audio != null) {
                audio.adjustVolumeBySteps(volumeUp ? 1 : -1);
                controller.getOverlay().onVolumeChanged(audio.getSteppedVolume(), audio.getSteppedMaxVolume(), audio.getBoostStep());
            }
        }

        return true;
    }
}
