/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.swipecontrols.controller.gesture;

import android.view.MotionEvent;

import androidx.annotation.Nullable;

import app.morphe.extension.youtube.swipecontrols.SwipeControlsConfigurationProvider.SwipeZoneAction;
import app.morphe.extension.youtube.swipecontrols.SwipeControlsHostActivity;
import app.morphe.extension.youtube.swipecontrols.controller.gesture.core.BaseGestureController;
import app.morphe.extension.youtube.swipecontrols.misc.Point;

/**
 * Provides the press-to-swipe (PtS) swipe controls experience.
 */
public final class PressToSwipeController extends BaseGestureController {

    /**
     * Where the current swipe session started, or null if there is no session.
     */
    @Nullable
    private Point swipeSessionOrigin;

    /**
     * @param controller Reference to the main swipe controller.
     */
    public PressToSwipeController(SwipeControlsHostActivity controller) {
        super(controller);
    }

    @Override
    protected boolean shouldForceInterceptEvents() {
        if (swipeSessionOrigin == null) return false;
        return swipeActionAt(swipeSessionOrigin, getCurrentSwipe()) != SwipeZoneAction.OFF;
    }

    @Override
    protected boolean shouldDropMotion(MotionEvent motionEvent) {
        return false;
    }

    @Override
    protected void onUp(MotionEvent motionEvent) {
        super.onUp(motionEvent);
        swipeSessionOrigin = null;
    }

    @Override
    public void onLongPress(MotionEvent motionEvent) {
        // enter swipe session with feedback
        swipeSessionOrigin = isInSwipeZone(motionEvent) ? Point.fromMotionEvent(motionEvent) : null;
        if (swipeSessionOrigin != null) {
            controller.getOverlay().onEnterSwipeSession();
        }

        // send GestureDetector a ACTION_CANCEL event so it will handle further events
        motionEvent.setAction(MotionEvent.ACTION_CANCEL);
        detector.onTouchEvent(motionEvent);
    }

    @Override
    protected boolean onSwipe(MotionEvent from, MotionEvent to, double distanceX, double distanceY) {
        // cancel if not fullscreen or multi-window
        if (!controller.getConfig().isFullscreenOrMultiWindowVideo()) return false;
        // cancel if not in swipe session
        if (swipeSessionOrigin == null) return false;

        return applySwipeAction(from, distanceX, distanceY);
    }
}
