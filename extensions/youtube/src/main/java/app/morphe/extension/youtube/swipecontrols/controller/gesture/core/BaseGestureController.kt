/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.swipecontrols.controller.gesture.core

import android.view.GestureDetector
import android.view.MotionEvent
import app.morphe.extension.youtube.settings.Settings.SwipeZoneAction
import app.morphe.extension.youtube.swipecontrols.SwipeControlsHostActivity
import app.morphe.extension.youtube.swipecontrols.misc.Point
import app.morphe.extension.youtube.swipecontrols.misc.contains
import app.morphe.extension.youtube.swipecontrols.misc.toPoint

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
        controller.config.speedStepInt,
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

        // evaluate swipe zone before recycling
        val inSwipeZone = isInSwipeZone(me)

        // invoke the custom onUp handler
        if (me.action == MotionEvent.ACTION_UP || me.action == MotionEvent.ACTION_CANCEL) {
            onUp(me)
        }

        // recycle the copy
        me.recycle()

        // do not consume dropped events
        // or events outside any swipe zone
        return !dropped && consumed && inSwipeZone
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
    fun isInSwipeZone(motionEvent: MotionEvent): Boolean {
        val point = motionEvent.toPoint()
        return (controller.config.topZoneAction != SwipeZoneAction.OFF && point in controller.zones.top) ||
            (controller.config.leftZoneAction != SwipeZoneAction.OFF && point in controller.zones.left) ||
            (controller.config.rightZoneAction != SwipeZoneAction.OFF && point in controller.zones.right)
    }

    /**
     * Resolves the action of the zone a swipe belongs to.
     *
     * The side zones own vertical swipes and the top zone owns horizontal ones. The zones overlap,
     * so the direction is what keeps that overlap unambiguous, and it leaves every other gesture
     * to the player instead of swallowing it.
     *
     * @param origin Where the swipe started.
     * @param direction The direction of the swipe.
     * @return The action to apply, or [SwipeZoneAction.OFF] if the swipe belongs to no zone.
     */
    protected fun swipeActionAt(origin: Point, direction: SwipeDetector.SwipeDirection): SwipeZoneAction =
        when (direction) {
            SwipeDetector.SwipeDirection.HORIZONTAL ->
                if (origin in controller.zones.top) controller.config.topZoneAction else SwipeZoneAction.OFF

            SwipeDetector.SwipeDirection.VERTICAL -> when (origin) {
                in controller.zones.left -> controller.config.leftZoneAction
                in controller.zones.right -> controller.config.rightZoneAction
                else -> SwipeZoneAction.OFF
            }

            else -> SwipeZoneAction.OFF
        }

    /**
     * Applies the action of the zone the swipe started in.
     *
     * @param from Start event of the swipe.
     * @param distanceX The horizontal distance of the swipe.
     * @param distanceY The vertical distance of the swipe.
     * @return Whether the swipe was consumed.
     */
    protected fun applySwipeAction(from: MotionEvent, distanceX: Double, distanceY: Double): Boolean {
        val direction = currentSwipe
        val distance = if (direction == SwipeDetector.SwipeDirection.HORIZONTAL) -distanceX else distanceY

        when (swipeActionAt(from.toPoint(), direction)) {
            SwipeZoneAction.VOLUME -> scrollVolume(distance)
            SwipeZoneAction.BRIGHTNESS -> scrollBrightness(distance)
            SwipeZoneAction.SPEED -> scrollSpeed(distance)
            SwipeZoneAction.OFF -> return false
        }

        return true
    }

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
