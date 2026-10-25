/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.swipecontrols.controller.gesture.core;

import android.view.GestureDetector;
import android.view.MotionEvent;

import androidx.annotation.Nullable;

import app.morphe.extension.youtube.swipecontrols.SwipeControlsConfigurationProvider;
import app.morphe.extension.youtube.swipecontrols.SwipeControlsConfigurationProvider.SwipeZoneAction;
import app.morphe.extension.youtube.swipecontrols.SwipeControlsHostActivity;
import app.morphe.extension.youtube.swipecontrols.controller.SwipeZonesController;
import app.morphe.extension.youtube.swipecontrols.controller.gesture.core.SwipeDetector.SwipeDirection;
import app.morphe.extension.youtube.swipecontrols.misc.Point;

/**
 * The common base of all {@link GestureController} classes.
 * Handles most of the boilerplate code needed for gesture detection.
 */
public abstract class BaseGestureController extends GestureDetector.SimpleOnGestureListener
        implements GestureController {

    /**
     * Reference to the main swipe controller.
     */
    protected final SwipeControlsHostActivity controller;

    private final SwipeDetector swipeDetector;

    private final VolumeAndBrightnessScroller scroller;

    /**
     * The main gesture detector that powers everything.
     */
    protected final GestureDetector detector;

    /**
     * Whether downstream events have been canceled; used in {@link #onScroll}.
     */
    private boolean didCancelDownstream;

    /**
     * @param controller Reference to the main swipe controller.
     */
    protected BaseGestureController(SwipeControlsHostActivity controller) {
        this.controller = controller;
        SwipeControlsConfigurationProvider config = controller.getConfig();
        swipeDetector = new SwipeDetector(config.getSwipeMagnitudeThreshold());
        scroller = new VolumeAndBrightnessScroller(
                controller,
                controller.getAudio(),
                controller.getScreen(),
                controller.getOverlay(),
                config.getVolumeSwipeDistance(),
                config.getBrightnessSwipeSensitivity(),
                config.getSpeedSwipeSensitivity(),
                config.getSpeedStepInt(),
                config.isSpeedControlEnabled()
        );
        detector = new GestureDetector(controller, this);
    }

    @Override
    public boolean submitTouchEvent(MotionEvent motionEvent) {
        SwipeControlsConfigurationProvider config = controller.getConfig();

        // Ignore if swipe is disabled.
        if (!config.isSwipeControlsEnabled()) {
            return false;
        }

        // Ignore if status bar is visible (unless the screen is shared with another app).
        if (controller.isStatusBarVisible() && !controller.isInSplitScreenMode()) {
            return false;
        }

        // Ignore if the native lock screen is engaged.
        if (config.shouldIgnoreSwipesWhenLocked() && SwipeControlsHostActivity.isPlayerLocked()) {
            return false;
        }

        // Create a copy of the event so we can modify it without causing any issues downstream.
        MotionEvent me = MotionEvent.obtain(motionEvent);

        // Check if we should drop this motion.
        final boolean dropped = shouldDropMotion(me);
        if (dropped) {
            me.setAction(MotionEvent.ACTION_CANCEL);
        }

        // Send the event to the detector if we force intercept events, the event is always consumed.
        final boolean consumed = detector.onTouchEvent(me) || shouldForceInterceptEvents();

        // Evaluate swipe zone before recycling.
        final boolean inSwipeZone = isInSwipeZone(me);

        // Invoke the custom onUp handler.
        if (me.getAction() == MotionEvent.ACTION_UP || me.getAction() == MotionEvent.ACTION_CANCEL) {
            onUp(me);
        }

        // Recycle the copy.
        me.recycle();

        // Do not consume dropped events or events outside any swipe zone.
        return !dropped && consumed && inSwipeZone;
    }

    /**
     * Custom handler for {@link MotionEvent#ACTION_UP} events, since GestureDetector doesn't provide one.
     *
     * @param motionEvent The motion event.
     */
    protected void onUp(MotionEvent motionEvent) {
        didCancelDownstream = false;
        swipeDetector.resetSwipe();
        scroller.resetScroller();
    }

    @Override
    public boolean onScroll(@Nullable MotionEvent from, MotionEvent to, float distanceX, float distanceY) {
        if (from == null) {
            return false;
        }

        // Submit to swipe detector.
        swipeDetector.submitForSwipe(from, to);

        // Call swipe callback if in a swipe.
        if (getCurrentSwipe() == SwipeDirection.NONE) {
            return false;
        }

        final boolean consumed = onSwipe(from, to, distanceX, distanceY);

        // if the swipe was consumed, cancel downstream events once
        if (consumed && !didCancelDownstream) {
            didCancelDownstream = true;
            MotionEvent cancelEvent = MotionEvent.obtain(from);
            cancelEvent.setAction(MotionEvent.ACTION_CANCEL);
            controller.dispatchDownstreamTouchEvent(cancelEvent);
            cancelEvent.recycle();
        }

        return consumed;
    }

    /**
     * The currently detected swipe.
     */
    protected SwipeDirection getCurrentSwipe() {
        return swipeDetector.getCurrentSwipe();
    }

    /**
     * Whether {@link #submitTouchEvent} should force-intercept all touch events.
     */
    protected abstract boolean shouldForceInterceptEvents();

    /**
     * Checks if the provided motion event is in any active swipe zone.
     *
     * @param motionEvent The event to check.
     * @return Whether the event is in any active swipe zone.
     */
    protected boolean isInSwipeZone(MotionEvent motionEvent) {
        SwipeControlsConfigurationProvider config = controller.getConfig();
        SwipeZonesController zones = controller.getZones();
        Point point = Point.fromMotionEvent(motionEvent);
        return (config.getTopZoneAction() != SwipeZoneAction.OFF && zones.getTop().contains(point))
                || (config.getLeftZoneAction() != SwipeZoneAction.OFF && zones.getLeft().contains(point))
                || (config.getRightZoneAction() != SwipeZoneAction.OFF && zones.getRight().contains(point));
    }

    /**
     * Resolves the action of the zone a swipe belongs to.
     * <p>
     * The side zones own vertical swipes and the top zone owns horizontal ones. The zones overlap,
     * so the direction is what keeps that overlap unambiguous, and it leaves every other gesture
     * to the player instead of swallowing it.
     *
     * @param origin    Where the swipe started.
     * @param direction The direction of the swipe.
     * @return The action to apply, or {@link SwipeZoneAction#OFF} if the swipe belongs to no zone.
     */
    protected SwipeZoneAction swipeActionAt(Point origin, SwipeDirection direction) {
        SwipeControlsConfigurationProvider config = controller.getConfig();
        SwipeZonesController zones = controller.getZones();

        if (direction == SwipeDirection.HORIZONTAL) {
            return zones.getTop().contains(origin)
                    ? config.getTopZoneAction()
                    : SwipeZoneAction.OFF;
        }

        if (direction == SwipeDirection.VERTICAL) {
            if (zones.getLeft().contains(origin)) {
                return config.getLeftZoneAction();
            }
            if (zones.getRight().contains(origin)) {
                return config.getRightZoneAction();
            }
        }

        return SwipeZoneAction.OFF;
    }

    /**
     * Applies the action of the zone the swipe started in.
     *
     * @param from      Start event of the swipe.
     * @param distanceX The horizontal distance of the swipe.
     * @param distanceY The vertical distance of the swipe.
     * @return Whether the swipe was consumed.
     */
    protected boolean applySwipeAction(MotionEvent from, double distanceX, double distanceY) {
        SwipeDirection direction = getCurrentSwipe();
        final double distance = direction == SwipeDirection.HORIZONTAL ? -distanceX : distanceY;

        switch (swipeActionAt(Point.fromMotionEvent(from), direction)) {
            case VOLUME -> scroller.scrollVolume(distance);
            case BRIGHTNESS -> scroller.scrollBrightness(distance);
            case SPEED -> scroller.scrollSpeed(distance);
            case OFF -> {
                return false;
            }
        }

        return true;
    }

    /**
     * Checks if a touch event should be dropped.
     * When an event is dropped, the gesture detector receives a {@link MotionEvent#ACTION_CANCEL} event and the event is not consumed.
     *
     * @param motionEvent The event to check.
     * @return Whether the event should be dropped.
     */
    protected abstract boolean shouldDropMotion(MotionEvent motionEvent);

    /**
     * Handler for swipe events, once a swipe is detected.
     * The direction of the swipe can be accessed in {@link #getCurrentSwipe()}.
     *
     * @param from      Start event of the swipe.
     * @param to        End event of the swipe.
     * @param distanceX The horizontal distance of the swipe.
     * @param distanceY The vertical distance of the swipe.
     * @return Whether the event was consumed.
     */
    protected abstract boolean onSwipe(MotionEvent from, MotionEvent to, double distanceX, double distanceY);
}
