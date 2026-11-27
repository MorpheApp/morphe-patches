/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4958
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.patches;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;

import app.morphe.extension.shared.Logger;

/**
 * Tracks the feeds and their scroll states, to find the feed on screen.
 */
@SuppressWarnings("unused")
public final class FeedHookPatch {

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
     * Scroll state of a feed, and when it was last shown.
     */
    private static final class Feed {
        final WeakReference<Object> stateRef;
        final long sequenceNumber;

        Feed(Object state, long sequenceNumber) {
            this.stateRef = new WeakReference<>(state);
            this.sequenceNumber = sequenceNumber;
        }
    }

    private static final String ON_SCREEN = "ON_SCREEN";

    /**
     * Feeds by their visibility flow, which stays the same when the app copies the feed context.
     */
    private static final Map<VisibilityFlow, Feed> feeds = new WeakHashMap<>();
    private static long nextSequenceNumber;

    private FeedHookPatch() {
    }

    private static boolean isEnabled() {
        return FeedAutoScrollPatch.isTrackingFeeds() || GlassNavigationBarTopTabs.isTrackingFeeds();
    }

    static boolean isOnScreen(VisibilityFlow flow) {
        final Object visibility = flow.patch_getValue();
        return visibility instanceof Enum<?> visibilityEnum && ON_SCREEN.equals(visibilityEnum.name());
    }

    /**
     * @return The visibility flow of the feed shown last that is on screen, or null if none is.
     */
    @Nullable
    static VisibilityFlow findOnScreenFeed() {
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

    /**
     * @return The scroll state of a feed, such as a Compose LazyListState.
     */
    @Nullable
    static Object getFeedState(VisibilityFlow flow) {
        final Feed feed = feeds.get(flow);
        return feed == null ? null : feed.stateRef.get();
    }

    /**
     * @return The scroll state of the feed on screen, or null if none is.
     */
    @Nullable
    static Object findOnScreenFeedState() {
        final VisibilityFlow flow = findOnScreenFeed();
        return flow == null ? null : getFeedState(flow);
    }

    private static void addFeed(@Nullable FeedContext feedContext, @Nullable Object state) {
        if (feedContext == null || state == null) {
            return;
        }

        final Feed previous = feeds.put(feedContext.patch_getFeedVisibilityFlow(),
                new Feed(state, nextSequenceNumber++));
        if (previous == null || previous.stateRef.get() != state) {
            onFeedsChanged();
        }
    }

    private static void onFeedsChanged() {
        FeedAutoScrollPatch.onFeedsChanged();
        GlassNavigationBarTopTabs.onFeedsChanged();
    }

    /**
     * Injection point.
     */
    public static void onListFeed(@Nullable FeedContext feedContext,
                                  @Nullable Object listState,
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
    public static void onGridFeed(@Nullable FeedContext feedContext, @Nullable Object gridState) {
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
            if (isEnabled()) {
                onFeedsChanged();
            }
        } catch (Exception ex) {
            Logger.printException(() -> "onFeedVisibilityChanged failure", ex);
        }
    }
}
