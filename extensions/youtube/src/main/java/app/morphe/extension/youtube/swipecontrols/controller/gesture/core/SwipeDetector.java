/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.swipecontrols.controller.gesture.core;

import android.view.MotionEvent;

/**
 * Detector that can detect swipes and their directionality.
 */
public final class SwipeDetector {

    /**
     * Direction of a swipe.
     */
    public enum SwipeDirection {
        /**
         * Swipe has no direction or no swipe detected.
         */
        NONE,

        /**
         * Swipe along the X axis.
         */
        HORIZONTAL,

        /**
         * Swipe along the Y axis.
         */
        VERTICAL
    }

    /**
     * Minimum magnitude before a swipe is detected as such.
     */
    private final double swipeMagnitudeThreshold;

    /**
     * The currently detected swipe.
     */
    private SwipeDirection currentSwipe = SwipeDirection.NONE;

    /**
     * @param swipeMagnitudeThreshold Minimum magnitude before a swipe is detected as such.
     */
    public SwipeDetector(double swipeMagnitudeThreshold) {
        this.swipeMagnitudeThreshold = swipeMagnitudeThreshold;
    }

    /**
     * The currently detected swipe.
     */
    public SwipeDirection getCurrentSwipe() {
        return currentSwipe;
    }

    /**
     * Submits an onScroll event for swipe detection.
     *
     * @param from Start event.
     * @param to   End event.
     */
    public void submitForSwipe(MotionEvent from, MotionEvent to) {
        if (currentSwipe == SwipeDirection.NONE) {
            // no swipe direction was detected yet, try to detect one
            // if the user did not swipe far enough, we cannot detect what direction they swiped
            // so we wait until a greater distance was swiped
            // NOTE: sqrt() can be high- cost, so using squared magnitudes here
            final float deltaX = Math.abs(to.getX() - from.getX());
            final float deltaY = Math.abs(to.getY() - from.getY());
            final float swipeMagnitudeSquared = deltaX * deltaX + deltaY * deltaY;
            if (swipeMagnitudeSquared > swipeMagnitudeThreshold * swipeMagnitudeThreshold) {
                currentSwipe = deltaY > deltaX
                        ? SwipeDirection.VERTICAL
                        : SwipeDirection.HORIZONTAL;
            }
        }
    }

    /**
     * Resets the swipe detection.
     */
    public void resetSwipe() {
        currentSwipe = SwipeDirection.NONE;
    }
}
