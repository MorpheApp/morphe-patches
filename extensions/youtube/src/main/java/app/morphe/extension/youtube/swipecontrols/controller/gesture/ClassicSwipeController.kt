package app.morphe.extension.youtube.swipecontrols.controller.gesture

import android.view.MotionEvent
import app.morphe.extension.youtube.shared.PlayerControlsVisibilityObserver
import app.morphe.extension.youtube.shared.PlayerControlsVisibilityObserverImpl
import app.morphe.extension.youtube.swipecontrols.SwipeControlsHostActivity
import app.morphe.extension.youtube.swipecontrols.controller.gesture.core.BaseGestureController
import app.morphe.extension.youtube.swipecontrols.controller.gesture.core.SwipeDetector
import app.morphe.extension.youtube.swipecontrols.misc.contains
import app.morphe.extension.youtube.swipecontrols.misc.toPoint

/**
 * Provides the classic swipe controls experience, as it was with 'XFenster'.
 *
 * @param controller Reference to the main swipe controller.
 */
class ClassicSwipeController(
    private val controller: SwipeControlsHostActivity,
) : BaseGestureController(controller),
    PlayerControlsVisibilityObserver by PlayerControlsVisibilityObserverImpl(controller) {
    /**
     * The last event captured in [onDown].
     */
    private var lastOnDownEvent: MotionEvent? = null

    override val shouldForceInterceptEvents: Boolean
        get() {
            val swipe = currentSwipe
            return swipe == SwipeDetector.SwipeDirection.VERTICAL ||
                    (controller.config.enableSpeedGestureControl &&
                     swipe == SwipeDetector.SwipeDirection.HORIZONTAL &&
                     lastOnDownEvent?.let { it.toPoint() in controller.zones.speed } == true)
        }

    override fun isInSwipeZone(motionEvent: MotionEvent): Boolean {
        val inVolumeZone = controller.config.enableVolumeControls &&
            (motionEvent.toPoint() in controller.zones.volume)
        val inBrightnessZone = controller.config.enableBrightnessControl &&
            (motionEvent.toPoint() in controller.zones.brightness)
        val inSpeedZone = controller.config.enableSpeedGestureControl &&
            (motionEvent.toPoint() in controller.zones.speed)
        return inVolumeZone || inBrightnessZone || inSpeedZone
    }

    override fun shouldDropMotion(motionEvent: MotionEvent): Boolean {
        // ignore gestures with more than one pointer
        // when such a gesture is detected, dispatch the first event of the gesture to downstream
        if (motionEvent.pointerCount > 1) {
            lastOnDownEvent?.let {
                controller.dispatchDownstreamTouchEvent(it)
                it.recycle()
            }
            lastOnDownEvent = null
            return true
        }

        // ignore gestures when player controls are visible
        return arePlayerControlsVisible
    }

    override fun onDown(motionEvent: MotionEvent): Boolean {
        // save the event for later
        lastOnDownEvent?.recycle()
        lastOnDownEvent = MotionEvent.obtain(motionEvent)

        // must be inside swipe zone
        return isInSwipeZone(motionEvent)
    }

    override fun onSingleTapUp(motionEvent: MotionEvent): Boolean {
        MotionEvent.obtain(motionEvent).let {
            it.action = MotionEvent.ACTION_DOWN
            controller.dispatchDownstreamTouchEvent(it)
            it.recycle()
        }

        return false
    }

    override fun onDoubleTapEvent(motionEvent: MotionEvent): Boolean {
        MotionEvent.obtain(motionEvent).let {
            controller.dispatchDownstreamTouchEvent(it)
            it.recycle()
        }

        return super.onDoubleTapEvent(motionEvent)
    }

    override fun onLongPress(motionEvent: MotionEvent) {
        MotionEvent.obtain(motionEvent).let {
            controller.dispatchDownstreamTouchEvent(it)
            it.recycle()
        }

        super.onLongPress(motionEvent)
    }

    override fun onSwipe(
        from: MotionEvent,
        to: MotionEvent,
        distanceX: Double,
        distanceY: Double,
    ): Boolean {
        // cancel if not fullscreen
        if (!controller.config.isFullscreenVideo) return false
        // cancel if not vertical or not a valid horizontal speed swipe
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
                if (fromPoint in controller.zones.speed) { scrollSpeed(-distanceX); true } else false
            else -> false
        }
    }
}
