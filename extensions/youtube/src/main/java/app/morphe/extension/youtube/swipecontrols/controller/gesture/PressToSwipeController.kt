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

    override val shouldForceInterceptEvents: Boolean
        get() = isInSwipeSession

    override fun shouldDropMotion(motionEvent: MotionEvent): Boolean = false

    override fun isInSwipeZone(motionEvent: MotionEvent): Boolean {
        val point = motionEvent.toPoint()
        val inLeft = controller.config.leftZoneAction != app.morphe.extension.youtube.settings.Settings.SwipeZoneAction.OFF &&
            (point in controller.zones.left)
        val inRight = controller.config.rightZoneAction != app.morphe.extension.youtube.settings.Settings.SwipeZoneAction.OFF &&
            (point in controller.zones.right)
        val inTop = controller.config.topZoneAction != app.morphe.extension.youtube.settings.Settings.SwipeZoneAction.OFF &&
            (point in controller.zones.top)
        return inLeft || inRight || inTop
    }

    override fun onUp(motionEvent: MotionEvent) {
        super.onUp(motionEvent)
        isInSwipeSession = false
    }

    override fun onLongPress(motionEvent: MotionEvent) {
        // enter swipe session with feedback
        isInSwipeSession = isInSwipeZone(motionEvent)
        if (isInSwipeSession) {
            controller.overlay.onEnterSwipeSession()
        }

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
        // cancel if not in swipe session
        if (!isInSwipeSession) return false
        val swipe = currentSwipe
        val fromPoint = from.toPoint()

        val action = when {
            fromPoint in controller.zones.top -> controller.config.topZoneAction
            fromPoint in controller.zones.left -> controller.config.leftZoneAction
            fromPoint in controller.zones.right -> controller.config.rightZoneAction
            else -> app.morphe.extension.youtube.settings.Settings.SwipeZoneAction.OFF
        }

        if (action == app.morphe.extension.youtube.settings.Settings.SwipeZoneAction.OFF) return false

        return when (action) {
            app.morphe.extension.youtube.settings.Settings.SwipeZoneAction.VOLUME -> {
                val dist = if (swipe == SwipeDetector.SwipeDirection.HORIZONTAL) -distanceX else distanceY
                scrollVolume(dist)
                true
            }
            app.morphe.extension.youtube.settings.Settings.SwipeZoneAction.BRIGHTNESS -> {
                val dist = if (swipe == SwipeDetector.SwipeDirection.HORIZONTAL) -distanceX else distanceY
                scrollBrightness(dist)
                true
            }
            app.morphe.extension.youtube.settings.Settings.SwipeZoneAction.SPEED -> {
                val dist = if (swipe == SwipeDetector.SwipeDirection.HORIZONTAL) -distanceX else distanceY
                scrollSpeed(dist)
                true
            }
            else -> false
        }
    }
}
