/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.swipecontrols.misc;

/**
 * Interface for all overlays for swipe controls.
 */
public interface SwipeControlsOverlay {
    /**
     * Called when the currently set volume level was changed.
     *
     * @param newVolume     The new volume level.
     * @param maximumVolume The maximum volume index.
     * @param boostStep     The volume boost above the maximum volume, in steps.
     */
    void onVolumeChanged(int newVolume, int maximumVolume, int boostStep);

    /**
     * Called when the currently set screen brightness was changed.
     *
     * @param brightness The new screen brightness, in percent (range 0.0-100.0).
     */
    void onBrightnessChanged(double brightness);

    /**
     * Called when the playback speed was changed via swipe gesture.
     *
     * @param speed The new playback speed multiplier.
     */
    void onSpeedChanged(float speed);

    /**
     * Called when a new swipe session has started.
     */
    void onEnterSwipeSession();
}
