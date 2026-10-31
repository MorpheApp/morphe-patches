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
}
