package app.morphe.extension.youtube.swipecontrols.controller.gesture.core

import android.view.GestureDetector
import android.view.MotionEvent
import app.morphe.extension.youtube.swipecontrols.SwipeControlsHostActivity

/**
 * The common base of all [GestureController] classes.
 * Handles most of the boilerplate code needed for gesture detection.
 *
 * @param controller Reference to the main swipe controller.
 */
abstract class BaseGestureController(
    private val controller: SwipeControlsHostActivity,
) : GestureController,
    GestureDetector.SimpleOnGestureListener(),
    SwipeDetector by SwipeDetectorImpl(
        controller.config.swipeMagnitudeThreshold.toDouble(),
    ),
    VolumeAndBrightnessScroller by VolumeAndBrightnessScrollerImpl(
        controller,
        controller.audio,
        controller.screen,
        controller.overlay,
        10,
        controller.config.brightnessSwipeSensitivity,
        controller.config.volumeSwipeSensitivity,
        controller.config.speedSwipeSensitivity,
        controller.config.enableSpeedGestureControl,
    ) {

    /**
     * The main gesture detector that powers everything.
     */
    @Suppress("LeakingThis")
    protected val detector = GestureDetector(controller, this)

    /**
     * Whether downstream events have been canceled; used in [onScroll].
     */
    private var didCancelDownstream = false

    override fun submitTouchEvent(motionEvent: MotionEvent): Boolean {
        // ignore if swipe is disabled
        if (!controller.config.enableSwipeControls) {
            return false
        }

        // ignore if status bar is visible
        if (controller.statusBarVisible) {
            return false
        }

        // create a copy of the event so we can modify it
        // without causing any issues downstream
        val me = MotionEvent.obtain(motionEvent)

        // check if we should drop this motion
        val dropped = shouldDropMotion(me)
        if (dropped) {
            me.action = MotionEvent.ACTION_CANCEL
        }

        // send the event to the detector
        // if we force intercept events, the event is always consumed
        val consumed = detector.onTouchEvent(me) || shouldForceInterceptEvents

        // invoke the custom onUp handler
        if (me.action == MotionEvent.ACTION_UP || me.action == MotionEvent.ACTION_CANCEL) {
            onUp(me)
        }

        // recycle the copy
        me.recycle()

        // do not consume dropped events
        // or events outside any swipe zone
        return !dropped && consumed && isInSwipeZone(me)
    }

    /**
     * Custom handler for [MotionEvent.ACTION_UP] events, since GestureDetector doesn't provide one.
     *
     * @param motionEvent The motion event.
     */
    open fun onUp(motionEvent: MotionEvent) {
        didCancelDownstream = false
        resetSwipe()
        resetScroller()
    }

    override fun onScroll(
        from: MotionEvent?,
        to: MotionEvent,
        distanceX: Float,
        distanceY: Float,
    ): Boolean {
        if (from == null) {
            return false
        }

        // submit to swipe detector
        submitForSwipe(from, to, distanceX, distanceY)

        // call swipe callback if in a swipe
        return if (currentSwipe != SwipeDetector.SwipeDirection.NONE) {
            val consumed = onSwipe(
                from,
                to,
                distanceX.toDouble(),
                distanceY.toDouble(),
            )

            // if the swipe was consumed, cancel downstream events once
            if (consumed && !didCancelDownstream) {
                didCancelDownstream = true
                MotionEvent.obtain(from).let {
                    it.action = MotionEvent.ACTION_CANCEL
                    controller.dispatchDownstreamTouchEvent(it)
                    it.recycle()
                }
            }

            consumed
        } else {
            false
        }
    }

    /**
     * Whether [submitTouchEvent] should force-intercept all touch events.
     */
    abstract val shouldForceInterceptEvents: Boolean

    /**
     * Checks if the provided motion event is in any active swipe zone.
     *
     * @param motionEvent The event to check.
     * @return Whether the event is in any active swipe zone.
     */
    abstract fun isInSwipeZone(motionEvent: MotionEvent): Boolean

    /**
     * Checks if a touch event should be dropped.
     * When an event is dropped, the gesture detector receives a [MotionEvent.ACTION_CANCEL] event and the event is not consumed.
     *
     * @param motionEvent The event to check.
     * @return Whether the event should be dropped.
     */
    abstract fun shouldDropMotion(motionEvent: MotionEvent): Boolean

    /**
     * Handler for swipe events, once a swipe is detected.
     * The direction of the swipe can be accessed in [currentSwipe].
     *
     * @param from Start event of the swipe.
     * @param to End event of the swipe.
     * @param distanceX The horizontal distance of the swipe.
     * @param distanceY The vertical distance of the swipe.
     * @return Whether the event was consumed.
     */
    abstract fun onSwipe(
        from: MotionEvent,
        to: MotionEvent,
        distanceX: Double,
        distanceY: Double,
    ): Boolean
}
