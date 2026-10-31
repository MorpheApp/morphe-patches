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
 * A simple rectangle class.
 */
public final class Rectangle {
    public final int x;
    public final int y;
    public final int width;
    public final int height;
    public final int left;
    public final int right;
    public final int top;
    public final int bottom;

    public Rectangle(int x, int y, int width, int height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        left = x;
        right = x + width;
        top = y;
        bottom = y + height;
    }

    /**
     * Checks if the point is within this rectangle.
     */
    public boolean contains(Point p) {
        return p.x() >= left && p.x() <= right && p.y() >= top && p.y() <= bottom;
    }
}
