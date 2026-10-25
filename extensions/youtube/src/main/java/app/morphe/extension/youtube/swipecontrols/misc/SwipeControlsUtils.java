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
