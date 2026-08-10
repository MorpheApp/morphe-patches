/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.swipecontrols.controller.gesture

import android.view.MotionEvent
import app.morphe.extension.youtube.settings.Settings.SwipeZoneAction
import app.morphe.extension.youtube.shared.PlayerControlsVisibilityObserver
import app.morphe.extension.youtube.shared.PlayerControlsVisibilityObserverImpl
import app.morphe.extension.youtube.swipecontrols.SwipeControlsHostActivity
import app.morphe.extension.youtube.swipecontrols.controller.gesture.core.BaseGestureController
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
            val origin = lastOnDownEvent?.toPoint() ?: return false
            return swipeActionAt(origin, currentSwipe) != SwipeZoneAction.OFF
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

    override fun onUp(motionEvent: MotionEvent) {
        super.onUp(motionEvent)
        lastOnDownEvent?.recycle()
        lastOnDownEvent = null
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
        // cancel if the swipe does not belong to any zone
        if (!shouldForceInterceptEvents) return false

        return applySwipeAction(from, distanceX, distanceY)
    }
}
