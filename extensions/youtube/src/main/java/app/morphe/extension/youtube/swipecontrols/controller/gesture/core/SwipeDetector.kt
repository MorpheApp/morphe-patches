package app.morphe.extension.youtube.swipecontrols.controller.gesture.core

import android.view.MotionEvent
import kotlin.math.abs
import kotlin.math.pow

/**
 * Describes a class that can detect swipes and their directionality.
 */
interface SwipeDetector {
    /**
     * The currently detected swipe.
     */
    val currentSwipe: SwipeDirection

    /**
     * Submits an onScroll event for swipe detection.
     *
     * @param from Start event.
     * @param to End event.
     * @param distanceX Horizontal scroll distance.
     * @param distanceY Vertical scroll distance.
     */
    fun submitForSwipe(
        from: MotionEvent,
        to: MotionEvent,
        distanceX: Float,
        distanceY: Float,
    )

    /**
     * Resets the swipe detection.
     */
    fun resetSwipe()

    /**
     * Direction of a swipe.
     */
    enum class SwipeDirection {
        /**
         * Swipe has no direction or no swipe detected.
         */
        NONE,

        /**
         * Swipe along the X axis.
         */
        HORIZONTAL,

        /**
         * Swipe along the Y axis.
         */
        VERTICAL,
    }
}

/**
 * Detector that can detect swipes and their directionality.
 *
 * @param swipeMagnitudeThreshold Minimum magnitude before a swipe is detected as such.
 */
class SwipeDetectorImpl(
    private val swipeMagnitudeThreshold: Double,
) : SwipeDetector {
    override var currentSwipe = SwipeDetector.SwipeDirection.NONE

    override fun submitForSwipe(
        from: MotionEvent,
        to: MotionEvent,
        distanceX: Float,
        distanceY: Float,
    ) {
        if (currentSwipe == SwipeDetector.SwipeDirection.NONE) {
            // no swipe direction was detected yet, try to detect one
            // if the user did not swipe far enough, we cannot detect what direction they swiped
            // so we wait until a greater distance was swiped
            // NOTE: sqrt() can be high- cost, so using squared magnitudes here
            val deltaX = abs(to.x - from.x)
            val deltaY = abs(to.y - from.y)
            val swipeMagnitudeSquared = deltaX.pow(2) + deltaY.pow(2)
            if (swipeMagnitudeSquared > swipeMagnitudeThreshold.pow(2)) {
                currentSwipe = if (deltaY > deltaX) {
                    SwipeDetector.SwipeDirection.VERTICAL
                } else {
                    SwipeDetector.SwipeDirection.HORIZONTAL
                }
            }
        }
    }

    override fun resetSwipe() {
        currentSwipe = SwipeDetector.SwipeDirection.NONE
    }
}
