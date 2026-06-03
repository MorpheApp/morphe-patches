package app.morphe.extension.youtube.swipecontrols.misc

import kotlin.math.abs
import kotlin.math.sign

/**
 * Helper for scaling the onScroll handler.
 *
 * @param unitDistance Absolute distance after which the callback is invoked.
 * @param callback Callback invoked when the unit distance is reached.
 */
class ScrollDistanceHelper(
    private val unitDistance: Int,
    private val callback: (oldDistance: Double, newDistance: Double, direction: Int) -> Unit,
) {

    /**
     * Total distance scrolled.
     */
    private var scrolledDistance: Double = 0.0

    /**
     * Adds a scrolled distance to the total.
     * If the [unitDistance] is reached, also invokes the callback.
     *
     * @param distance The distance to add.
     */
    fun add(distance: Double) {
        scrolledDistance += distance

        // invoke the callback if we scrolled far enough
        while (abs(scrolledDistance) >= unitDistance) {
            val oldDistance = scrolledDistance
            subtractUnitDistance()
            callback.invoke(
                oldDistance,
                scrolledDistance,
                sign(scrolledDistance).toInt(),
            )
        }
    }

    /**
     * Resets the distance scrolled to zero.
     */
    fun reset() {
        scrolledDistance = 0.0
    }

    /**
     * Subtracts the [unitDistance] from the total [scrolledDistance].
     */
    private fun subtractUnitDistance() {
        scrolledDistance -= (unitDistance * sign(scrolledDistance))
    }
}
