package app.morphe.extension.youtube.swipecontrols.misc

/**
 * A simple rectangle class.
 */
data class Rectangle(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
) {
    val left = x
    val right = x + width
    val top = y
    val bottom = y + height
}

/**
 * Checks if the point is within this rectangle.
 */
operator fun Rectangle.contains(p: Point): Boolean =
    p.x in left..right && p.y in top..bottom
