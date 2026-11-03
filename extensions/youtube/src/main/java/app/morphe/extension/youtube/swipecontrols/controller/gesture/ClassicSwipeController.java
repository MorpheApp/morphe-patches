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

import app.morphe.extension.youtube.shared.PlayerControlsVisibilityObserver;
import app.morphe.extension.youtube.swipecontrols.SwipeControlsConfigurationProvider.SwipeZoneAction;
import app.morphe.extension.youtube.swipecontrols.SwipeControlsHostActivity;
import app.morphe.extension.youtube.swipecontrols.controller.gesture.core.BaseGestureController;
import app.morphe.extension.youtube.swipecontrols.controller.gesture.core.SwipeDetector.SwipeDirection;
import app.morphe.extension.youtube.swipecontrols.misc.Point;

/**
 * Provides the classic swipe controls experience, as it was with 'XFenster'.
 */
public final class ClassicSwipeController extends BaseGestureController {

    private final PlayerControlsVisibilityObserver playerControlsVisibilityObserver;

    /**
     * The last event captured in {@link #onDown}.
     */
    @Nullable
    private MotionEvent lastOnDownEvent;

    /**
     * @param controller Reference to the main swipe controller.
     */
    public ClassicSwipeController(SwipeControlsHostActivity controller) {
        super(controller);
        playerControlsVisibilityObserver = new PlayerControlsVisibilityObserver(controller);
    }

    @Override
    protected boolean shouldForceInterceptEvents() {
        if (lastOnDownEvent == null) return false;
        Point origin = Point.fromMotionEvent(lastOnDownEvent);
        SwipeDirection direction = getCurrentSwipe();
        if (direction == SwipeDirection.HORIZONTAL && isSeekGesture(origin)) {
            return true;
        }
        return swipeActionAt(origin, direction) != SwipeZoneAction.OFF;
    }

    @Override
    protected boolean shouldDropMotion(MotionEvent motionEvent) {
        // ignore gestures with more than one pointer
        // when such a gesture is detected, dispatch the first event of the gesture to downstream
        if (motionEvent.getPointerCount() > 1) {
            if (lastOnDownEvent != null) {
                lastOnDownEvent.recycle();
            }
            lastOnDownEvent = null;
            return true;
        }

        // ignore gestures when player controls are visible
        return playerControlsVisibilityObserver.arePlayerControlsVisible();
    }

    @Override
    protected void onUp(MotionEvent motionEvent) {
        super.onUp(motionEvent);
        if (lastOnDownEvent != null) {
            lastOnDownEvent.recycle();
        }
        lastOnDownEvent = null;
    }

    @Override
    public boolean onDown(MotionEvent motionEvent) {
        // save the event for later
        if (lastOnDownEvent != null) {
            lastOnDownEvent.recycle();
        }
        lastOnDownEvent = MotionEvent.obtain(motionEvent);

        // Deliver DOWN once, even when direction arbitration later leaves the gesture to YouTube.
        boolean inZone = isInSwipeZone(motionEvent);
        if (inZone) {
            controller.dispatchDownstreamTouchEvent(motionEvent);
        }
        return inZone;
    }

    @Override
    protected boolean onSwipe(MotionEvent from, MotionEvent to, double distanceX, double distanceY) {
        // cancel if not fullscreen or multi-window
        if (!controller.getConfig().isFullscreenOrMultiWindowVideo()) return false;
        // cancel if the swipe does not belong to any zone
        if (!shouldForceInterceptEvents()) return false;

        return applySwipeAction(from, to, distanceX, distanceY);
    }
}
