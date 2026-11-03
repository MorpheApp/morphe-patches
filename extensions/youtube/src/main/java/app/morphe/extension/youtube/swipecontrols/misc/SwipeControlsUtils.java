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

import android.content.Context;
import android.util.TypedValue;

public final class SwipeControlsUtils {

    /**
     * Converts a value in the given unit to pixels.
     *
     * @param unit  The unit of the value, one of the TypedValue.COMPLEX_UNIT_* constants.
     * @param value The value to convert.
     */
    public static int applyDimension(Context context, int unit, int value) {
        return Math.round(TypedValue.applyDimension(
                unit,
                value,
                context.getResources().getDisplayMetrics()
        ));
    }

    /**
     * Formats a duration in milliseconds to a time string (e.g. "01:23" or "01:02:03").
     *
     * @param ms The duration in milliseconds.
     * @return Formatted time string.
     */
    public static String formatTime(long ms) {
        long totalSeconds = Math.max(0, ms / 1000);
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        if (hours > 0) {
            return String.format(java.util.Locale.ROOT, "%02d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(java.util.Locale.ROOT, "%02d:%02d", minutes, seconds);
    }
    /** Signed seconds, with tenths for fine seeking and no snapping to fixed jump intervals. */
    public static String formatSeekSeconds(long offsetMs) {
        String value = String.format(java.util.Locale.ROOT, "%+.1f", offsetMs / 1000d);
        return value.endsWith(".0") ? value.substring(0, value.length() - 2) : value;
    }

}
