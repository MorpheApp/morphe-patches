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

import android.view.MotionEvent;

/**
 * A simple 2D point class.
 */
public record Point(int x, int y) {

    /**
     * Converts the motion event coordinates to a point.
     */
    public static Point fromMotionEvent(MotionEvent event) {
        return new Point((int) event.getX(), (int) event.getY());
    }
}
