/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3516
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.patches;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;

import app.morphe.extension.reddit.settings.Settings;
import app.morphe.extension.shared.Logger;

@SuppressWarnings("unused")
public final class FeedAutoScrollPatch {

    public enum ScrollSpeed {
        SLOW(40),
        MEDIUM(80),
        FAST(160),
        VERY_FAST(280);

        final int dpPerSecond;

        ScrollSpeed(int dpPerSecond) {
            this.dpPerSecond = dpPerSecond;
        }
    }

    public interface OnScrollStateChangedListener {
        void onScrollStateChanged(boolean scrolling);
    }

    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final String ON_SCREEN_NAME = "ON_SCREEN";
    private static final long MAX_FRAME_GAP_MS = 100;
    private static final float MILLIS_PER_SECOND = 1000f;

    private static final class FeedScrollTarget {
        final WeakReference<Object> stateRef;
        final boolean isGrid;
        final long sequenceNumber;

        FeedScrollTarget(Object state, boolean isGrid, long sequenceNumber) {
            this.stateRef = new WeakReference<>(state);
            this.isGrid = isGrid;
            this.sequenceNumber = sequenceNumber;
        }
    }

    private static final Map<Object, FeedScrollTarget> targets = new WeakHashMap<>();
    private static long nextSequenceNumber;

    @Nullable
    private static Object pendingGridFlow;

    private static boolean isScrolling;
    @Nullable
    private static Object activeFlow;
    @Nullable
    private static FeedScrollTarget activeTarget;
    private static float speedPxPerSec;
    private static long startTimeMs;
    private static long lastFrameTimeMs;
    private static float lastDistancePx;

    @Nullable
    private static OnScrollStateChangedListener scrollStateChangedListener;

    private static final Choreographer.FrameCallback FRAME_CALLBACK = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            try {
                if (!isScrolling || activeTarget == null || activeFlow == null) {
                    return;
                }

                if (!isOnScreen(activeFlow)) {
                    stop();
                    return;
                }

                final Object state = activeTarget.stateRef.get();
                if (state == null) {
                    stop();
                    return;
                }

                final long now = SystemClock.uptimeMillis();
                final long gap = now - lastFrameTimeMs;
                if (gap > MAX_FRAME_GAP_MS) {
                    startTimeMs += gap;
                }
                lastFrameTimeMs = now;

                final float elapsedSeconds = Math.max(0f, (now - startTimeMs) / MILLIS_PER_SECOND);
                final float totalDistance = speedPxPerSec * elapsedSeconds;
                final float delta = totalDistance - lastDistancePx;
                lastDistancePx = totalDistance;

                if (delta > 0f) {
                    if (activeTarget.isGrid) {
                        dispatchGridScroll(state, delta);
                    } else {
                        dispatchListScroll(state, delta);
                    }
                }

                if (isScrolling) {
                    Choreographer.getInstance().postFrameCallback(this);
                }
            } catch (Exception ex) {
                Logger.printException(() -> "doFrame failure", ex);
                stop();
            }
        }
    };

    private FeedAutoScrollPatch() {}

    public static boolean isPatchIncluded() {
        return false; // Modified during patching.
    }

    @Nullable
    public static Object getVisibilityFlow(@Nullable Object feedContext) {
        return null; // Modified during patching.
    }

    @Nullable
    public static Object getFlowValue(@Nullable Object flow) {
        return null; // Modified during patching.
    }

    public static float dispatchListScroll(@Nullable Object state, float delta) {
        return 0f; // Modified during patching.
    }

    public static float dispatchGridScroll(@Nullable Object state, float delta) {
        return 0f; // Modified during patching.
    }

    public static boolean isEnabled() {
        return isPatchIncluded() && Settings.FEED_AUTO_SCROLL.get();
    }

    public static boolean isScrolling() {
        return isScrolling;
    }

    public static boolean hasOnScreenFeed() {
        try {
            for (Map.Entry<Object, FeedScrollTarget> entry : targets.entrySet()) {
                final Object flow = entry.getKey();
                final FeedScrollTarget target = entry.getValue();
                if (flow != null && target != null && isOnScreen(flow) && target.stateRef.get() != null) {
                    return true;
                }
            }
            return false;
        } catch (Exception ex) {
            Logger.printException(() -> "hasOnScreenFeed failure", ex);
            return false;
        }
    }

    public static boolean start(@NonNull Context context) {
        try {
            if (!isEnabled()) {
                return false;
            }
            if (Looper.myLooper() != Looper.getMainLooper()) {
                MAIN_HANDLER.post(() -> start(context));
                return false;
            }

            Object bestFlow = null;
            FeedScrollTarget bestTarget = null;
            for (Map.Entry<Object, FeedScrollTarget> entry : targets.entrySet()) {
                final Object flow = entry.getKey();
                final FeedScrollTarget target = entry.getValue();
                if (flow != null && target != null && isOnScreen(flow) && target.stateRef.get() != null) {
                    if (bestTarget == null || target.sequenceNumber > bestTarget.sequenceNumber) {
                        bestFlow = flow;
                        bestTarget = target;
                    }
                }
            }

            if (bestTarget == null) {
                Logger.printDebug(() -> "no on-screen feed");
                return false;
            }

            if (isScrolling) {
                stop();
            }

            activeFlow = bestFlow;
            activeTarget = bestTarget;
            final float speedDp = Settings.FEED_AUTO_SCROLL_SPEED.get().dpPerSecond;
            speedPxPerSec = speedDp * context.getResources().getDisplayMetrics().density;
            startTimeMs = SystemClock.uptimeMillis();
            lastFrameTimeMs = startTimeMs;
            lastDistancePx = 0f;
            isScrolling = true;
            notifyScrollStateChanged(true);
            Choreographer.getInstance().postFrameCallback(FRAME_CALLBACK);
            return true;
        } catch (Exception ex) {
            Logger.printException(() -> "start failure", ex);
            return false;
        }
    }

    public static void stop() {
        try {
            if (Looper.myLooper() != Looper.getMainLooper()) {
                MAIN_HANDLER.post(FeedAutoScrollPatch::stop);
                return;
            }
            if (!isScrolling) {
                return;
            }
            isScrolling = false;
            activeFlow = null;
            activeTarget = null;
            Choreographer.getInstance().removeFrameCallback(FRAME_CALLBACK);
            notifyScrollStateChanged(false);
        } catch (Exception ex) {
            Logger.printException(() -> "stop failure", ex);
        }
    }

    public static void setOnScrollStateChangedListener(@Nullable OnScrollStateChangedListener listener) {
        scrollStateChangedListener = listener;
    }

    private static void notifyScrollStateChanged(boolean scrolling) {
        if (scrollStateChangedListener != null) {
            try {
                scrollStateChangedListener.onScrollStateChanged(scrolling);
            } catch (Exception ex) {
                Logger.printException(() -> "onScrollStateChanged failure", ex);
            }
        }
    }

    private static boolean isOnScreen(@Nullable Object flow) {
        if (flow == null) {
            return false;
        }
        final Object value = getFlowValue(flow);
        return value instanceof Enum<?> && ON_SCREEN_NAME.equals(((Enum<?>) value).name());
    }

    /**
     * Injection point.
     */
    public static void onListFeed(@Nullable Object feedContext, @Nullable Object listState, @Nullable Object feedType) {
        try {
            if (!isEnabled()) {
                return;
            }
            if (Looper.myLooper() != Looper.getMainLooper()) {
                MAIN_HANDLER.post(() -> onListFeed(feedContext, listState, feedType));
                return;
            }
            if (feedContext == null || listState == null) {
                return;
            }
            if (TabletGridFeedPatch.isPatchIncluded()
                    && feedType instanceof Enum<?>
                    && TabletGridFeedPatch.showGridFeed((Enum<?>) feedType)) {
                return;
            }
            final Object flow = getVisibilityFlow(feedContext);
            if (flow == null) {
                return;
            }
            targets.put(flow, new FeedScrollTarget(listState, false, nextSequenceNumber++));
            FeedAutoScrollButton.updateVisibility();
        } catch (Exception ex) {
            Logger.printException(() -> "onListFeed failure", ex);
        }
    }

    /**
     * Injection point.
     */
    public static void onGridFeedContext(@Nullable Object feedContext) {
        try {
            if (!isEnabled()) {
                return;
            }
            if (Looper.myLooper() != Looper.getMainLooper()) {
                MAIN_HANDLER.post(() -> onGridFeedContext(feedContext));
                return;
            }
            if (feedContext == null) {
                pendingGridFlow = null;
                return;
            }
            pendingGridFlow = getVisibilityFlow(feedContext);
        } catch (Exception ex) {
            Logger.printException(() -> "onGridFeedContext failure", ex);
        }
    }

    /**
     * Injection point.
     */
    public static void onGridState(@Nullable Object gridState) {
        try {
            if (!isEnabled()) {
                return;
            }
            if (Looper.myLooper() != Looper.getMainLooper()) {
                MAIN_HANDLER.post(() -> onGridState(gridState));
                return;
            }
            if (gridState != null && pendingGridFlow != null) {
                targets.put(pendingGridFlow, new FeedScrollTarget(gridState, true, nextSequenceNumber++));
                pendingGridFlow = null;
                FeedAutoScrollButton.updateVisibility();
            }
        } catch (Exception ex) {
            Logger.printException(() -> "onGridState failure", ex);
        }
    }

    /**
     * Injection point.
     */
    public static void onFeedVisibilityChanged() {
        try {
            if (!isEnabled()) {
                return;
            }
            if (Looper.myLooper() != Looper.getMainLooper()) {
                MAIN_HANDLER.post(FeedAutoScrollPatch::onFeedVisibilityChanged);
                return;
            }
            if (isScrolling && activeFlow != null && !isOnScreen(activeFlow)) {
                stop();
            }
            FeedAutoScrollButton.updateVisibility();
        } catch (Exception ex) {
            Logger.printException(() -> "onFeedVisibilityChanged failure", ex);
        }
    }

    /**
     * Injection point.
     */
    public static void onMainActivityResumed(@Nullable Activity activity) {
        try {
            FeedAutoScrollButton.onMainActivityResumed(activity);
        } catch (Exception ex) {
            Logger.printException(() -> "onMainActivityResumed failure", ex);
        }
    }

    /**
     * Injection point.
     */
    public static void onMainActivityPaused(@Nullable Activity activity) {
        try {
            FeedAutoScrollButton.onMainActivityPaused(activity);
        } catch (Exception ex) {
            Logger.printException(() -> "onMainActivityPaused failure", ex);
        }
    }

    /**
     * Injection point.
     */
    public static void onNavBarFraction(float fraction) {
        try {
            FeedAutoScrollButton.onNavBarFraction(fraction);
        } catch (Exception ex) {
            Logger.printException(() -> "onNavBarFraction failure", ex);
        }
    }

    /**
     * Injection point.
     */
    public static void onPostPageViewCreated(@Nullable View view) {
        try {
            FeedAutoScrollButton.onPostPageViewCreated(view);
        } catch (Exception ex) {
            Logger.printException(() -> "onPostPageViewCreated failure", ex);
        }
    }
}
