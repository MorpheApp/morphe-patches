/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5006
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.playback.speed;

/** A leftward drag selects up to ten seconds over a third of the width of the player. */
public final class ReplaySwipeGesture {
    public static final int MAX_REWIND_SECONDS = 10;
    private static final float FULL_REWIND_WIDTH_FRACTION = 1f / 3;
    private static final float HORIZONTAL_DIRECTION_RATIO = 1.5f;

    private boolean tracking;
    private boolean swiping;
    private float startX;
    private float startY;
    private float fullRewindDistance;
    private float touchSlop;
    private long downTime;
    private long longPressTimeout;
    private int seconds;

    public void start(float x, float y, float playerWidth, float touchSlop,
                      long eventTime, long longPressTimeout) {
        cancel();
        if (playerWidth <= 0) return;
        tracking = true;
        startX = x;
        startY = y;
        fullRewindDistance = playerWidth * FULL_REWIND_WIDTH_FRACTION;
        this.touchSlop = touchSlop;
        downTime = eventTime;
        this.longPressTimeout = longPressTimeout;
    }

    public boolean move(float x, float y, long eventTime) {
        if (!tracking) return false;
        final float leftDistance = startX - x;
        final float verticalDistance = Math.abs(y - startY);
        if (!swiping) {
            // Leave rightward, vertical and long-press gestures to the existing controls.
            if (leftDistance < -touchSlop || (verticalDistance > touchSlop
                    && verticalDistance >= Math.abs(leftDistance))
                    || eventTime - downTime >= longPressTimeout) {
                cancel();
                return false;
            }
            if (leftDistance <= touchSlop
                    || leftDistance < verticalDistance * HORIZONTAL_DIRECTION_RATIO) {
                return false;
            }
            swiping = true;
        }
        seconds = leftDistance <= touchSlop ? 0
                : Math.max(1, Math.min(MAX_REWIND_SECONDS,
                        Math.round(leftDistance / fullRewindDistance * MAX_REWIND_SECONDS)));
        return true;
    }

    public boolean isSwiping() {
        return swiping;
    }

    public int getSeconds() {
        return seconds;
    }

    public int finish() {
        final int result = swiping ? seconds : 0;
        cancel();
        return result;
    }

    public void cancel() {
        tracking = false;
        swiping = false;
        seconds = 0;
    }
}
