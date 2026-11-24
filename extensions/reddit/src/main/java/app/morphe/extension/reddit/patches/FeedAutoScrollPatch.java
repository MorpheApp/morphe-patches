/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4958
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.patches;

import android.app.Activity;
import android.content.Context;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.View;

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

    /**
     * Feed context of a feed composable. Interface added during patching.
     */
    public interface FeedContext {
        VisibilityFlow patch_getFeedVisibilityFlow();
    }

    /**
     * StateFlow with the FeedVisibility of a feed. Interface added during patching.
     */
    public interface VisibilityFlow {
        Object patch_getValue();
    }

    /**
     * Compose ScrollableState of a list or grid feed. Interface added during patching.
     */
    public interface ScrollableState {
        float patch_dispatchRawDelta(float delta);
    }

    /**
     * Scroll state of a feed, and when it was last shown.
     */
    private static final class Feed {
        final WeakReference<ScrollableState> stateRef;
        final long sequenceNumber;

        Feed(ScrollableState state, long sequenceNumber) {
            this.stateRef = new WeakReference<>(state);
            this.sequenceNumber = sequenceNumber;
        }
    }

    private static final String ON_SCREEN = "ON_SCREEN";

    /**
     * A longer time between frames, such as when the app was in the background,
     * does not scroll the feed further.
     */
    private static final long MAX_FRAME_GAP_MS = 100;

    private static final float MILLIS_PER_SECOND = 1000f;

    /**
     * Feeds by their visibility flow, which stays the same when the app copies the feed context.
     */
    private static final Map<VisibilityFlow, Feed> feeds = new WeakHashMap<>();
    private static long nextSequenceNumber;

    @Nullable
    private static VisibilityFlow activeFlow;
    @Nullable
    private static ScrollableState activeState;
    private static float speedPxPerSecond;
    private static long startTimeMs;
    private static long lastFrameTimeMs;
    private static float scrolledDistancePx;

    private static final Choreographer.FrameCallback FRAME_CALLBACK = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            try {
                if (activeFlow == null || activeState == null) {
                    return;
                }

                if (!isOnScreen(activeFlow)) {
                    stop();
                    return;
                }

                final long now = SystemClock.uptimeMillis();
                final long gap = now - lastFrameTimeMs;
                if (gap > MAX_FRAME_GAP_MS) {
                    startTimeMs += gap;
                }
                lastFrameTimeMs = now;

                final float distance = speedPxPerSecond * (now - startTimeMs) / MILLIS_PER_SECOND;
                final float delta = distance - scrolledDistancePx;
                scrolledDistancePx = distance;
                if (delta > 0) {
                    activeState.patch_dispatchRawDelta(delta);
                }

                Choreographer.getInstance().postFrameCallback(this);
            } catch (Exception ex) {
                Logger.printException(() -> "doFrame failure", ex);
                stop();
            }
        }
    };

    public static boolean isPatchIncluded() {
        return false; // Modified during patching.
    }

    static boolean isEnabled() {
        return Settings.FEED_AUTO_SCROLL.get();
    }

    static boolean isScrolling() {
        return activeState != null;
    }

    private static boolean isOnScreen(VisibilityFlow flow) {
        final Object visibility = flow.patch_getValue();
        return visibility instanceof Enum<?> && ON_SCREEN.equals(((Enum<?>) visibility).name());
    }

    /**
     * @return The visibility flow of the feed shown last that is on screen, or null if none is.
     */
    @Nullable
    private static VisibilityFlow findOnScreenFeed() {
        VisibilityFlow bestFlow = null;
        long bestSequenceNumber = -1;

        for (Map.Entry<VisibilityFlow, Feed> entry : feeds.entrySet()) {
            final VisibilityFlow flow = entry.getKey();
            final Feed feed = entry.getValue();
            if (flow != null && feed.sequenceNumber > bestSequenceNumber
                    && feed.stateRef.get() != null && isOnScreen(flow)) {
                bestFlow = flow;
                bestSequenceNumber = feed.sequenceNumber;
            }
        }

        return bestFlow;
    }

    static boolean hasOnScreenFeed() {
        return findOnScreenFeed() != null;
    }

    static void start(Context context) {
        stop();

        final VisibilityFlow flow = findOnScreenFeed();
        if (flow == null) {
            Logger.printDebug(() -> "No feed on screen");
            return;
        }
        final Feed feed = feeds.get(flow);
        final ScrollableState state = feed == null ? null : feed.stateRef.get();
        if (state == null) {
            return;
        }

        activeFlow = flow;
        activeState = state;
        speedPxPerSecond = Settings.FEED_AUTO_SCROLL_SPEED.get().dpPerSecond
                * context.getResources().getDisplayMetrics().density;
        startTimeMs = SystemClock.uptimeMillis();
        lastFrameTimeMs = startTimeMs;
        scrolledDistancePx = 0;

        Choreographer.getInstance().postFrameCallback(FRAME_CALLBACK);
        FeedAutoScrollButton.onScrollStateChanged();
    }

    static void stop() {
        if (!isScrolling()) {
            return;
        }

        activeFlow = null;
        activeState = null;

        Choreographer.getInstance().removeFrameCallback(FRAME_CALLBACK);
        FeedAutoScrollButton.onScrollStateChanged();
    }

    private static void addFeed(@Nullable FeedContext feedContext, @Nullable ScrollableState state) {
        if (feedContext == null || state == null) {
            return;
        }

        feeds.put(feedContext.patch_getFeedVisibilityFlow(), new Feed(state, nextSequenceNumber++));
        FeedAutoScrollButton.updateVisibility();
    }

    /**
     * Injection point.
     */
    public static void onListFeed(@Nullable FeedContext feedContext,
                                  @Nullable ScrollableState listState,
                                  @Nullable Enum<?> feedType) {
        try {
            if (!isEnabled()) {
                return;
            }

            // The grid feed is shown instead, and is added by onGridFeed().
            if (TabletGridFeedPatch.isPatchIncluded() && TabletGridFeedPatch.showGridFeed(feedType)) {
                return;
            }

            addFeed(feedContext, listState);
        } catch (Exception ex) {
            Logger.printException(() -> "onListFeed failure", ex);
        }
    }

    /**
     * Injection point.
     */
    public static void onGridFeed(@Nullable FeedContext feedContext, @Nullable ScrollableState gridState) {
        try {
            if (isEnabled()) {
                addFeed(feedContext, gridState);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "onGridFeed failure", ex);
        }
    }

    /**
     * Injection point. Called after the visibility flow of the feed is updated.
     */
    public static void onFeedVisibilityChanged() {
        try {
            if (!isEnabled()) {
                return;
            }

            if (activeFlow != null && !isOnScreen(activeFlow)) {
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
    public static void onMainActivityResumed(Activity activity) {
        try {
            if (isEnabled()) {
                FeedAutoScrollButton.onMainActivityResumed(activity);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "onMainActivityResumed failure", ex);
        }
    }

    /**
     * Injection point.
     */
    public static void onMainActivityPaused() {
        try {
            if (isEnabled()) {
                FeedAutoScrollButton.onMainActivityPaused();
            }
        } catch (Exception ex) {
            Logger.printException(() -> "onMainActivityPaused failure", ex);
        }
    }

    /**
     * Injection point.
     *
     * @param fraction How much of the navigation bar is shown, from 0 to 1.
     */
    public static void onNavBarStateChange(float fraction) {
        try {
            if (isEnabled()) {
                // Disable bottom bar auto-hide hooks the same method and its hook can run after this one.
                FeedAutoScrollButton.onNavBarStateChange(
                        DisableBottomBarAutoHidePatch.getNavBarShownFraction(fraction));
            }
        } catch (Exception ex) {
            Logger.printException(() -> "onNavBarStateChange failure", ex);
        }
    }

    /**
     * Injection point.
     */
    public static void onPostDetailPagerViewCreated(View view) {
        try {
            if (isEnabled()) {
                FeedAutoScrollButton.onPostDetailPagerViewCreated(view);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "onPostDetailPagerViewCreated failure", ex);
        }
    }

    /**
     * Injection point.
     */
    public static void onScreenAttached(Object screen) {
        try {
            if (isEnabled()) {
                FeedAutoScrollButton.onScreenAttachChanged(screen, true);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "onScreenAttached failure", ex);
        }
    }

    /**
     * Injection point.
     */
    public static void onScreenDetached(Object screen) {
        try {
            if (isEnabled()) {
                FeedAutoScrollButton.onScreenAttachChanged(screen, false);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "onScreenDetached failure", ex);
        }
    }
}
