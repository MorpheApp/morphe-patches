package app.morphe.extension.youtube.swipecontrols.controller.gesture

import android.view.MotionEvent
import app.morphe.extension.youtube.swipecontrols.SwipeControlsHostActivity
import app.morphe.extension.youtube.swipecontrols.controller.gesture.core.BaseGestureController
import app.morphe.extension.youtube.swipecontrols.controller.gesture.core.SwipeDetector
import app.morphe.extension.youtube.swipecontrols.misc.contains
import app.morphe.extension.youtube.swipecontrols.misc.toPoint

/**
 * Provides the press-to-swipe (PtS) swipe controls experience.
 *
 * @param controller Reference to the main swipe controller.
 */
class PressToSwipeController(
    private val controller: SwipeControlsHostActivity,
) : BaseGestureController(controller) {
    /**
     * Indicates whether the user is currently in a swipe session.
     */
    private var isInSwipeSession = false

    /**
     * Indicates whether the current swipe session was initiated in the speed zone.
     */
    private var isInSpeedSwipeSession = false

    override val shouldForceInterceptEvents: Boolean
        get() {
            val swipe = currentSwipe
            return (swipe == SwipeDetector.SwipeDirection.VERTICAL && isInSwipeSession) ||
                    (swipe == SwipeDetector.SwipeDirection.HORIZONTAL && isInSpeedSwipeSession)
        }

    override fun shouldDropMotion(motionEvent: MotionEvent): Boolean = false

    override fun isInSwipeZone(motionEvent: MotionEvent): Boolean {
        val inVolumeZone = controller.config.enableVolumeControls &&
            (motionEvent.toPoint() in controller.zones.volume)
        val inBrightnessZone = controller.config.enableBrightnessControl &&
            (motionEvent.toPoint() in controller.zones.brightness)
        val inSpeedZone = controller.config.enableSpeedGestureControl &&
            (motionEvent.toPoint() in controller.zones.speed)
        return inVolumeZone || inBrightnessZone || inSpeedZone
    }

    override fun onUp(motionEvent: MotionEvent) {
        super.onUp(motionEvent)
        isInSwipeSession = false
        isInSpeedSwipeSession = false
    }

    override fun onLongPress(motionEvent: MotionEvent) {
        // enter swipe session with feedback
        isInSwipeSession = true
        isInSpeedSwipeSession = controller.config.enableSpeedGestureControl &&
            motionEvent.toPoint() in controller.zones.speed
        controller.overlay.onEnterSwipeSession()

        // send GestureDetector a ACTION_CANCEL event so it will handle further events
        motionEvent.action = MotionEvent.ACTION_CANCEL
        detector.onTouchEvent(motionEvent)
    }

    override fun onSwipe(
        from: MotionEvent,
        to: MotionEvent,
        distanceX: Double,
        distanceY: Double,
    ): Boolean {
        // cancel if not fullscreen
        if (!controller.config.isFullscreenVideo) return false
        // cancel if not in swipe session or valid direction
        if (!shouldForceInterceptEvents) return false
        val swipe = currentSwipe
        val fromPoint = from.toPoint()
        return when (swipe) {
            SwipeDetector.SwipeDirection.VERTICAL -> when (fromPoint) {
                in controller.zones.volume -> { scrollVolume(distanceY); true }
                in controller.zones.brightness -> { scrollBrightness(distanceY); true }
                else -> false
            }
            SwipeDetector.SwipeDirection.HORIZONTAL ->
                if (isInSpeedSwipeSession) { scrollSpeed(-distanceX); true } else false
            else -> false
        }
    }
}
