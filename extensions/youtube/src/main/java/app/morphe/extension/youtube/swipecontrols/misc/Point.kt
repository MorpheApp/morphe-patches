package app.morphe.extension.youtube.swipecontrols.misc

import android.view.MotionEvent

/**
 * A simple 2D point class.
 */
data class Point(
    val x: Int,
    val y: Int,
)

/**
 * Converts the motion event coordinates to a point.
 */
fun MotionEvent.toPoint(): Point =
    Point(x.toInt(), y.toInt())
