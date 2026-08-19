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
import app.morphe.extension.youtube.swipecontrols.SwipeControlsConfigurationProvider.SwipeZoneAction
import app.morphe.extension.youtube.swipecontrols.SwipeControlsHostActivity
import app.morphe.extension.youtube.swipecontrols.controller.gesture.core.BaseGestureController
import app.morphe.extension.youtube.swipecontrols.misc.Point
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
     * Where the current swipe session started, or null if there is no session.
     */
    private var swipeSessionOrigin: Point? = null

    override val shouldForceInterceptEvents: Boolean
        get() {
            val origin = swipeSessionOrigin ?: return false
            return swipeActionAt(origin, currentSwipe) != SwipeZoneAction.OFF
        }

    override fun shouldDropMotion(motionEvent: MotionEvent): Boolean = false

    override fun onUp(motionEvent: MotionEvent) {
        super.onUp(motionEvent)
        swipeSessionOrigin = null
    }

    override fun onLongPress(motionEvent: MotionEvent) {
        // enter swipe session with feedback
        swipeSessionOrigin = if (isInSwipeZone(motionEvent)) motionEvent.toPoint() else null
        if (swipeSessionOrigin != null) {
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
        // cancel if not fullscreen or multi-window
        if (!controller.config.isFullscreenOrMultiWindowVideo) return false
        // cancel if not in swipe session
        if (swipeSessionOrigin == null) return false

        return applySwipeAction(from, distanceX, distanceY)
    }
}
