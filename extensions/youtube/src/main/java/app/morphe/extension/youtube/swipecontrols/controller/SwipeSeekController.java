/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4364
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.swipecontrols.controller;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.youtube.patches.VideoInformation;

/**
 * Seeks with horizontal swipes by scrubbing the time bar of the player,
 * so the app shows its own time bar, seek preview and chapter while swiping.
 */
public final class SwipeSeekController {

    private static WeakReference<SeekbarScrubber> scrubberRef = new WeakReference<>(null);

    private static boolean swipeSeeking;

    /**
     * Injection point.
     */
    public static void setScrubber(SeekbarScrubber scrubber) {
        scrubberRef = new WeakReference<>(scrubber);
    }

    /**
     * @return If a seek swipe is in progress.
     */
    public static boolean isSwipeSeeking() {
        return swipeSeeking;
    }

    public static boolean canSeek() {
        return !VideoInformation.isLiveStream() && VideoInformation.getVideoLength() > 0;
    }

    /**
     * Scrubber of the swipe in progress.
     */
    @Nullable
    private SeekbarScrubber scrubber;

    /**
     * Scrubber position when the swipe started.
     */
    private int startX;

    /**
     * Horizontal distance swiped since the swipe started.
     */
    private float swipedX;

    private int lastX;

    public boolean isScrubbing() {
        return scrubber != null;
    }

    /**
     * @return If scrubbing started.
     */
    public boolean start() {
        SeekbarScrubber current = scrubberRef.get();
        if (current == null) {
            Logger.printDebug(() -> "Cannot swipe to seek, the time bar is not available");
            return false;
        }

        // Set first, as the app checks it when scrubbing starts.
        swipeSeeking = true;
        try {
            startX = current.patch_startScrub();
        } catch (Exception ex) {
            Logger.printException(() -> "start failure", ex);
            swipeSeeking = false;
            return false;
        }

        scrubber = current;
        swipedX = 0;
        lastX = startX;
        return true;
    }

    /**
     * @param distanceX Horizontal distance swiped since the last call. Positive values move forward.
     */
    public void move(float distanceX) {
        if (scrubber == null) return;

        swipedX += distanceX;
        final int x = startX + Math.round(swipedX);
        if (x == lastX) return;

        try {
            scrubber.patch_moveScrub(x);
            lastX = x;
        } catch (Exception ex) {
            Logger.printException(() -> "move failure", ex);
        }
    }

    /**
     * Seeks to where the swipe ended, or back to where it started if the swipe was cancelled.
     */
    public void end(boolean cancelled) {
        if (scrubber == null) return;

        try {
            scrubber.patch_completeScrub(cancelled ? startX : lastX);
        } catch (Exception ex) {
            Logger.printException(() -> "end failure", ex);
        }
        scrubber = null;
        swipeSeeking = false;
    }
}
