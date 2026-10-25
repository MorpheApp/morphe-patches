package app.morphe.extension.youtube.swipecontrols.misc;

import android.view.MotionEvent;

/**
 * A simple 2D point class.
 */
public final class Point {
    public final int x;
    public final int y;

    public Point(int x, int y) {
        this.x = x;
        this.y = y;
    }

    /**
     * Converts the motion event coordinates to a point.
     */
    public static Point fromMotionEvent(MotionEvent event) {
        return new Point((int) event.getX(), (int) event.getY());
    }
}
