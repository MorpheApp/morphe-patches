/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2269
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

/** The plain arithmetic a line draws with, kept out of the view so it can be tested on its own. */
final class LyricsLineMath {

    /** A closed background vocal is drawn this much smaller, so it grows out of its lead line. */
    private static final float SECONDARY_CLOSED_SCALE = 0.85f;

    private LyricsLineMath() {
    }

    static float secondaryScale(float fraction) {
        return SECONDARY_CLOSED_SCALE + (1f - SECONDARY_CLOSED_SCALE) * fraction;
    }

    /** Finds which syllable's horizontal range contains x, or the nearest one. */
    static int findSyllableByX(float x, float[] leadX, float[] trailX) {
        for (int i = 0; i < leadX.length; i++) {
            if (x >= leadX[i] && x <= trailX[i]) {
                return i;
            }
        }
        // Touch is between syllables; return the nearest by midpoint.
        int nearest = -1;
        float nearestDist = Float.MAX_VALUE;
        for (int i = 0; i < leadX.length; i++) {
            float mid = (leadX[i] + trailX[i]) / 2f;
            float dist = Math.abs(x - mid);
            if (dist < nearestDist) {
                nearestDist = dist;
                nearest = i;
            }
        }
        return nearest;
    }

    static int alpha255(float alpha) {
        return Math.max(0, Math.min(255, Math.round(255f * alpha)));
    }

    /** Right to left lines fill leftwards, so their furthest point is the smallest one. */
    static float extendSung(float current, float edge, boolean rightToLeft) {
        if (Float.isNaN(current)) {
            return edge;
        }
        return rightToLeft ? Math.min(current, edge) : Math.max(current, edge);
    }
}
