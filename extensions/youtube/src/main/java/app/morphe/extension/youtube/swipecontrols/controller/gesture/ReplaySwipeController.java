/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5006
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.swipecontrols.controller.gesture;

import android.graphics.Rect;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import androidx.annotation.Nullable;

import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.youtube.patches.VideoInformation;
import app.morphe.extension.youtube.patches.onthego.OnTheGoPatch;
import app.morphe.extension.youtube.patches.playback.speed.ReplaySwipeGesture;
import app.morphe.extension.youtube.shared.PlayerControlsVisibilityObserver;
import app.morphe.extension.youtube.shared.PlayerType;
import app.morphe.extension.youtube.swipecontrols.SwipeControlsConfigurationProvider.SwipeZoneAction;
import app.morphe.extension.youtube.swipecontrols.SwipeControlsHostActivity;
import app.morphe.extension.youtube.swipecontrols.controller.gesture.core.GestureController;
import app.morphe.extension.youtube.swipecontrols.misc.Point;
import app.morphe.extension.youtube.videoplayer.ReplayVideoButton;

/** Recognizes replay drags inside the normal or fullscreen player without taking taps or pinches. */
public final class ReplaySwipeController implements GestureController {
    private static final int EDGE_PADDING_DP = 20;
    private static final int VERTICAL_PADDING_DP = 24;

    private final SwipeControlsHostActivity host;
    private final ReplaySwipeGesture gesture = new ReplaySwipeGesture();
    private final PlayerControlsVisibilityObserver controls;
    private final Rect playerBounds = new Rect();
    private final int playerOverlayId;
    private final int touchSlop;
    private final int edgePadding;
    private final int verticalPadding;
    @Nullable
    private MotionEvent initialDown;
    private String videoId = "";
    private int previewSeconds = -1;

    public ReplaySwipeController(SwipeControlsHostActivity host) {
        this.host = host;
        controls = new PlayerControlsVisibilityObserver(host);
        playerOverlayId = ResourceUtils.getIdentifier(host, ResourceType.ID, "inset_controls_overlay_wrapper");
        touchSlop = ViewConfiguration.get(host).getScaledTouchSlop();
        final float density = host.getResources().getDisplayMetrics().density;
        edgePadding = Math.round(EDGE_PADDING_DP * density);
        verticalPadding = Math.round(VERTICAL_PADDING_DP * density);
    }

    @Override
    public boolean submitTouchEvent(MotionEvent event) {
        final int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            cancel();
            if (canStart(event)) {
                videoId = VideoInformation.getVideoId();
                initialDown = MotionEvent.obtain(event);
                gesture.start(event.getRawX(), event.getRawY(), playerBounds.width(), touchSlop,
                        event.getEventTime(), ViewConfiguration.getLongPressTimeout());
            }
            return false;
        }
        if (initialDown == null) return false;

        final boolean wasSwiping = gesture.isSwiping();
        if (event.getPointerCount() > 1) {
            // Both existing detectors were canceled when this drag took ownership.
            // Restore a DOWN for the player before forwarding the pinch's POINTER_DOWN.
            if (wasSwiping) host.dispatchDownstreamTouchEvent(initialDown);
            cancel();
            return false;
        }
        if (!canReplay() || !videoId.equals(VideoInformation.getVideoId())) {
            cancel();
            return wasSwiping;
        }
        if (action == MotionEvent.ACTION_CANCEL) {
            cancel();
            return wasSwiping;
        }
        if (action == MotionEvent.ACTION_UP) {
            if (wasSwiping) gesture.move(event.getRawX(), event.getRawY(), event.getEventTime());
            final int seconds = gesture.finish();
            cancel();
            if (seconds > 0) ReplayVideoButton.replayFromSwipe(seconds);
            return wasSwiping;
        }
        if (action != MotionEvent.ACTION_MOVE
                || !gesture.move(event.getRawX(), event.getRawY(), event.getEventTime())) {
            return false;
        }
        if (!wasSwiping) {
            MotionEvent canceled = MotionEvent.obtain(initialDown);
            canceled.setAction(MotionEvent.ACTION_CANCEL);
            try {
                host.cancelTouchGestures(canceled);
            } finally {
                canceled.recycle();
            }
            host.onUserInteraction();
        }
        final int seconds = gesture.getSeconds();
        if (seconds != previewSeconds) {
            previewSeconds = seconds;
            host.getOverlay().showReplayFeedback(seconds, playerBounds);
        }
        return true;
    }

    private boolean canReplay() {
        PlayerType type = PlayerType.getCurrent();
        return (type == PlayerType.WATCH_WHILE_FULLSCREEN || type == PlayerType.WATCH_WHILE_MAXIMIZED)
                && !VideoInformation.lastVideoIdIsShort() && !VideoInformation.isLiveStream()
                && VideoInformation.getVideoTime() > 0 && VideoInformation.getVideoLength() > 0
                && !OnTheGoPatch.isOnTheGoActive() && !SwipeControlsHostActivity.isPlayerLocked();
    }

    private boolean canStart(MotionEvent event) {
        if (!event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN) || !canReplay()
                || controls.arePlayerControlsVisible()) {
            return false;
        }
        View player = host.findViewById(playerOverlayId);
        if (player == null || !player.getGlobalVisibleRect(playerBounds)) return false;
        final float x = event.getRawX();
        final float y = event.getRawY();
        if (x < playerBounds.left + edgePadding || x > playerBounds.right - edgePadding
                || y < playerBounds.top + verticalPadding || y > playerBounds.bottom - verticalPadding) {
            return false;
        }
        if (!host.getConfig().isFullscreenOrMultiWindowVideo()) return true;
        if (host.isStatusBarVisible() && !host.isInSplitScreenMode()) return false;
        // Existing seek and top-zone gestures keep ownership of their horizontal swipes.
        Point point = Point.fromMotionEvent(event);
        return (host.getConfig().getTopZoneAction() == SwipeZoneAction.OFF
                || !host.getZones().getTop().contains(point))
                && (!host.getConfig().isSwipeSeekEnabled()
                || !host.getZones().getEffectiveSwipeRect().contains(point));
    }

    public void cancel() {
        gesture.cancel();
        if (previewSeconds >= 0) host.getOverlay().hideReplayFeedback();
        previewSeconds = -1;
        if (initialDown != null) initialDown.recycle();
        initialDown = null;
    }
}
