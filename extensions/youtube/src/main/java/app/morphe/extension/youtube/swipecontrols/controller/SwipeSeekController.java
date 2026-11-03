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

    /**
     * Distance the scrubber is moved without its time changing, at which it is at the start or end of the time bar.
     * More than one pixel, as the time bar can scale the position, which can leave the time unchanged for a pixel.
     */
    private static final int EDGE_PIXELS = 2;

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
     * How far the scrubber moves for each pixel swiped.
     */
    private float sensitivity;

    /**
     * Distance the scrubber moved from the start position.
     */
    private float scrubbedX;

    /**
     * Last position the scrubber was moved to.
     */
    private int lastX;

    /**
     * Last position that changed the time of the scrubber, and that time.
     */
    private int lastTimeX;
    private long lastTime;

    public boolean isScrubbing() {
        return scrubber != null;
    }

    /**
     * @param sensitivity How far the scrubber moves for each pixel swiped. 1 moves it as far as the finger.
     * @return If scrubbing started.
     */
    public boolean start(float sensitivity) {
        SeekbarScrubber current = scrubberRef.get();
        if (current == null) {
            Logger.printDebug(() -> "Cannot swipe to seek, the time bar is not available");
            return false;
        }

        // Set first, as the app checks it when scrubbing starts.
        swipeSeeking = true;
        try {
            startX = current.patch_startScrub();
            // Moving to the same position does not move the scrubber, and gets its time.
            lastTime = current.patch_moveScrub(startX);
        } catch (Exception ex) {
            Logger.printException(() -> "start failure", ex);
            swipeSeeking = false;
            return false;
        }

        scrubber = current;
        this.sensitivity = sensitivity;
        scrubbedX = 0;
        lastX = startX;
        lastTimeX = startX;
        return true;
    }

    /**
     * @param distanceX Horizontal distance swiped since the last call. Positive values move forward.
     */
    public void move(float distanceX) {
        if (scrubber == null) return;

        scrubbedX += distanceX * sensitivity;
        final int x = startX + Math.round(scrubbedX);
        if (x == lastX) return;

        try {
            final long time = scrubber.patch_moveScrub(x);

            lastX = x;
            if (time != lastTime) {
                lastTime = time;
                lastTimeX = x;
            } else if (Math.abs(x - lastTimeX) >= EDGE_PIXELS) {
                // The scrubber is at the start or end of the time bar.
                // Continue from the end, so swiping back moves it right away.
                scrubbedX = lastTimeX - startX;
                lastX = lastTimeX;
            }
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
