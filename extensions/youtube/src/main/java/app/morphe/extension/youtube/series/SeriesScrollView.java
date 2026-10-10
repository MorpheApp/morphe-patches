/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3114
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.series;

import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ScrollView;

/** Keeps short libraries able to expand the native History header through nested scrolling. */
final class SeriesScrollView extends ScrollView {
    private boolean checkingNestedGesture;

    SeriesScrollView(Context context) {
        super(context);
        setNestedScrollingEnabled(true);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            startNestedScroll(View.SCROLL_AXIS_VERTICAL);
        }
        // ScrollView skips gesture tracking when its own content fits. The enclosing app bar
        // can still consume a drag, especially after collapsing made the viewport taller.
        // Bypass that early-out only during interception and only with a nested parent.
        checkingNestedGesture = hasNestedScrollingParent();
        try {
            return super.onInterceptTouchEvent(event);
        } finally {
            checkingNestedGesture = false;
        }
    }

    @Override
    public boolean canScrollVertically(int direction) {
        return super.canScrollVertically(direction)
                || (checkingNestedGesture && direction > 0 && getChildCount() > 0);
    }
}
