/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4364
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.swipecontrols.controller;

/**
 * Scrubs the player time bar, the same way slide to seek does.
 * Added to the slide to seek touch handler of the app during patching.
 * <p>
 * Positions are x coordinates of the time bar scrubber, in pixels.
 */
public interface SeekbarScrubber {
    /**
     * Starts scrubbing at the current playback position.
     *
     * @return Position of the scrubber.
     */
    int patch_startScrub();

    /**
     * @return Time of the scrubber after moving it, in milliseconds.
     *         It does not change while x is past the start or end of the time bar.
     */
    long patch_moveScrub(int x);

    /**
     * Ends scrubbing, and seeks to the time at the position.
     */
    void patch_completeScrub(int x);
}
