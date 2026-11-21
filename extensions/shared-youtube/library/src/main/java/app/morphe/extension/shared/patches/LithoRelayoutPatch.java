/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3384
 * https://github.com/MorpheApp/morphe-patches/pull/3447
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.shared.patches;

import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.text.Spanned;
import android.view.Choreographer;
import android.view.View;
import android.view.ViewTreeObserver;

import androidx.annotation.Nullable;

import com.facebook.litho.TextContent;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * Forces Litho views to calculate their layout again, which reloads the view.
 * Litho otherwise keeps the calculated layout until the view is bound again,
 * such as after scrolling the view off-screen and back.
 * <p>
 * Only the texts with a {@link RelayoutSpan} are laid out again, such as loading texts.
 * The mounted text drawables of these texts are remembered, so a relayout checks only
 * these drawables and lays out only the Litho views that show an outdated text.
 * <p>
 * The shown Litho views of lists can also be mounted again, such as to load again the images that
 * failed to load. Laying out or binding a view again does not load its images again,
 * as the image components do not change. When the views that show the images are known,
 * only the Litho views of these views are mounted again.
 * <p>
 * Laying out a view again is calculated on the main thread, so the requests made while a list scrolls,
 * such as the titles and thumbnails of the elements scrolled on screen, are done with a single pass
 * once the list stops scrolling. The elements bound to a view meanwhile already show what was fetched,
 * and the texts unmounted meanwhile are not laid out again. A fast scroll, such as a fling,
 * is waited for longer than a slow scroll, and the views are laid out again one per frame,
 * so a single frame does not calculate the layouts of all the views.
 */
@SuppressWarnings("unused")
public final class LithoRelayoutPatch {

    /**
     * Interface added to the Litho view class during patching.
     */
    public interface LithoViewInterface {
        /**
         * Forces the Litho view to calculate the layout again.
         */
        void patch_forceRelayout();

        /**
         * Unmounts all content of the Litho view, which is mounted again by the next layout.
         */
        void patch_forceRemount();
    }

    /**
     * Span of a Litho text that is laid out again when outdated, such as a loading text.
     */
    public interface RelayoutSpan {
        /**
         * Called on the main thread.
         *
         * @return If the text is outdated, and laying it out again changes it.
         */
        boolean isOutdated();

        /**
         * Called on the main thread.
         *
         * @return If the host that shows this outdated text can be laid out again. A recycled view
         *         can still hold a text of another screen, and laying it out changes that screen.
         */
        default boolean canRelayoutHost(View host) {
            return true;
        }
    }

    /**
     * Relayouts requested at about the same time, such as titles fetched at the same time,
     * are done with a single pass.
     */
    private static final long RELAYOUT_DELAY_MILLISECONDS = 100;

    /**
     * Time without scrolling after which a list is no longer scrolling.
     */
    private static final long SCROLL_IDLE_MILLISECONDS = 150;

    /**
     * Longest time a relayout waits for the lists to stop scrolling, so the texts are still updated
     * while a list keeps scrolling, such as a slow scroll or a live chat that scrolls to new messages.
     */
    private static final long MAX_SCROLL_WAIT_MILLISECONDS = 500;

    /**
     * Longest time a relayout waits for a fast scroll to slow down, such as a fling.
     */
    private static final long MAX_FAST_SCROLL_WAIT_MILLISECONDS = 2000;

    /**
     * Speed above which a scroll is fast, in density-independent pixels per millisecond.
     * A fling is faster, and a slow scroll or a live chat that scrolls to new messages is slower.
     */
    private static final float FAST_SCROLL_DP_PER_MILLISECOND = 0.5f;

    /**
     * Mounted text drawables that showed a text with a {@link RelayoutSpan}.
     * Accessed only on the main thread.
     */
    private static final Set<Drawable> relayoutTextDrawables = Collections.newSetFromMap(new WeakHashMap<>());

    /**
     * Litho views that were measured. Accessed only on the main thread.
     */
    private static final Set<View> lithoViews = Collections.newSetFromMap(new WeakHashMap<>());

    /**
     * If a relayout is scheduled. Requests can be made on different threads at the same time,
     * such as titles fetched at the same time, so it's set atomically and a single relayout is scheduled.
     */
    private static final AtomicBoolean relayoutScheduled = new AtomicBoolean();

    /**
     * Time the scheduled relayout was requested. Set on any thread before the relayout runs.
     */
    private static volatile long relayoutRequestTime;

    /**
     * Last time a view of the windows that show Litho views scrolled. Accessed only on the main thread.
     */
    private static long lastScrollTime;

    /**
     * Tree observers of the windows the scroll listener was added to. Accessed only on the main thread.
     */
    private static final Set<ViewTreeObserver> scrollObservers = Collections.newSetFromMap(new WeakHashMap<>());

    /**
     * Item of a scrolling list, whose position gives the speed of the scroll. Accessed only on the main thread.
     */
    private static WeakReference<View> scrollReferenceView = new WeakReference<>(null);
    private static final int[] scrollReferenceLocation = new int[2];

    /**
     * Speed of the last scroll, in density-independent pixels per millisecond,
     * or 0 if not known. Accessed only on the main thread.
     */
    private static float scrollSpeed;

    private static final ViewTreeObserver.OnScrollChangedListener scrollListener = () -> {
        final long now = SystemClock.uptimeMillis();
        final long elapsed = now - lastScrollTime;
        lastScrollTime = now;
        if (!relayoutScheduled.get()) {
            return;
        }
        View reference = scrollReferenceView.get();
        final int lastX = scrollReferenceLocation[0];
        final int lastY = scrollReferenceLocation[1];
        if (reference == null || !reference.isShown()) {
            // Litho views of list items, which move while their list scrolls.
            reference = null;
            for (View view : lithoViews) {
                if (view.isShown() && view.getParent() instanceof View parent
                        && (parent.canScrollVertically(1) || parent.canScrollVertically(-1)
                        || parent.canScrollHorizontally(1) || parent.canScrollHorizontally(-1))) {
                    reference = view;
                    break;
                }
            }
            scrollReferenceView = new WeakReference<>(reference);
            scrollSpeed = 0;
            if (reference != null) {
                reference.getLocationInWindow(scrollReferenceLocation);
            }
            return;
        }
        reference.getLocationInWindow(scrollReferenceLocation);
        if (elapsed > 0 && elapsed < SCROLL_IDLE_MILLISECONDS) {
            final int distance = Math.max(Math.abs(scrollReferenceLocation[0] - lastX),
                    Math.abs(scrollReferenceLocation[1] - lastY));
            scrollSpeed = distance / (reference.getResources().getDisplayMetrics().density * elapsed);
        } else {
            scrollSpeed = 0;
        }
    };

    /**
     * Litho views waiting to be laid out again, or mounted again, one per frame.
     * Accessed only on the main thread.
     */
    private static final Set<LithoViewInterface> pendingRelayoutViews = new LinkedHashSet<>();
    private static final Set<LithoViewInterface> pendingRemountViews = new LinkedHashSet<>();

    /**
     * Views mounted again since the pending views started to be processed. A nested Litho view
     * of a view mounted again is already mounted again. Accessed only on the main thread.
     */
    private static final Set<View> remountedViews = Collections.newSetFromMap(new WeakHashMap<>());

    private static boolean pendingViewsFrameCallbackPosted;

    private static final Choreographer.FrameCallback pendingViewsFrameCallback = frameTimeNanos -> {
        pendingViewsFrameCallbackPosted = false;
        processNextPendingView();
    };

    /**
     * Called on the main thread when a Litho view is measured, such as when a list item is scrolled
     * on screen, or null if none. Not called while the app is idle, so it does not keep the app busy.
     */
    @Nullable
    private static volatile Runnable lithoViewMeasuredListener;

    /**
     * If the shown Litho views of lists are mounted again by the next relayout. Requested on any thread,
     * such as the network threads, so it's read and cleared atomically and no request is lost.
     */
    private static final AtomicBoolean remountListViewsRequested = new AtomicBoolean();

    /**
     * Views whose Litho views are mounted again by the next relayout, such as the views of images
     * that load again. Requested on any thread, such as the network threads.
     */
    private static final Set<View> remountViewsRequested =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    private LithoRelayoutPatch() {
    }

    /**
     * Forces the Litho views that show an outdated text to calculate the layout again.
     * Can be called on any thread.
     */
    public static void relayoutOutdatedTexts() {
        scheduleRelayout();
    }

    /**
     * Mounts again the content of the shown Litho views of lists, which loads their images again.
     * Same as when a list item is scrolled off-screen and bound again to a view.
     * Views outside of lists, such as the player overlay, are not mounted again.
     * Can be called on any thread.
     */
    public static void remountListViews() {
        remountListViewsRequested.set(true);
        scheduleRelayout();
    }

    /**
     * Mounts again the content of the Litho views that show the views, if shown,
     * such as the views of the images to load again. Can be called on any thread.
     */
    public static void remountListViews(Collection<View> views) {
        if (!views.isEmpty()) {
            remountViewsRequested.addAll(views);
            scheduleRelayout();
        }
    }

    /**
     * Must be called on the main thread.
     *
     * @return The {@link RelayoutSpan} of the class of the shown Litho texts,
     *         such as the texts that are on screen and not scrolled off-screen.
     */
    public static <T extends RelayoutSpan> Set<T> findShownSpans(Class<T> spanClass) {
        Set<T> shownSpans = new HashSet<>();
        for (Drawable textDrawable : new ArrayList<>(relayoutTextDrawables)) {
            // The callback is the host, or null if the text was unmounted.
            if (!(textDrawable.getCallback() instanceof View host) || !host.isShown()
                    || !(textDrawable instanceof TextContent textContent)) {
                continue;
            }
            for (CharSequence text : textContent.getTextItems()) {
                if (text instanceof Spanned spanned) {
                    Collections.addAll(shownSpans, spanned.getSpans(0, spanned.length(), spanClass));
                }
            }
        }
        return shownSpans;
    }

    private static void scheduleRelayout() {
        if (relayoutScheduled.compareAndSet(false, true)) {
            relayoutRequestTime = SystemClock.uptimeMillis();
            // Delayed, so the layouts calculated in the current frame are mounted.
            Utils.runOnMainThreadDelayed(LithoRelayoutPatch::relayoutOutdatedViews, RELAYOUT_DELAY_MILLISECONDS);
        }
    }

    /**
     * Injection point.
     * <p>
     * Called on the main thread when a Litho view is measured.
     */
    public static void onLithoViewMeasured(View lithoView) {
        lithoViews.add(lithoView);
        if (relayoutScheduled.get() && lithoView.isAttachedToWindow()) {
            // Views of a window share its tree observer, so the listener is added once per window,
            // and only after a relayout was requested.
            ViewTreeObserver observer = lithoView.getViewTreeObserver();
            if (observer.isAlive() && scrollObservers.add(observer)) {
                observer.addOnScrollChangedListener(scrollListener);
            }
        }
        Runnable listener = lithoViewMeasuredListener;
        if (listener != null) {
            listener.run();
        }
    }

    /**
     * Sets the listener called on the main thread when a Litho view is measured, or null to remove it.
     * The listener must be fast, as Litho views are measured while scrolling.
     */
    public static void setLithoViewMeasuredListener(@Nullable Runnable listener) {
        lithoViewMeasuredListener = listener;
    }

    /**
     * Injection point.
     * <p>
     * Called on the main thread before the text drawable is added to its host, so the host is found later.
     */
    public static void onLithoTextMounted(Drawable textDrawable, CharSequence text) {
        try {
            Boolean outdated = isOutdated(text, null);
            if (outdated != null) {
                relayoutTextDrawables.add(textDrawable);
                // The text can be outdated before it's mounted, such as a title fetched meanwhile.
                if (outdated) {
                    relayoutOutdatedTexts();
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "onLithoTextMounted failure", ex);
        }
    }

    private static void relayoutOutdatedViews() {
        final long now = SystemClock.uptimeMillis();
        final long scrollIdleTime = lastScrollTime + SCROLL_IDLE_MILLISECONDS;
        final long maxWaitTime = relayoutRequestTime + (scrollSpeed > FAST_SCROLL_DP_PER_MILLISECOND
                ? MAX_FAST_SCROLL_WAIT_MILLISECONDS
                : MAX_SCROLL_WAIT_MILLISECONDS);
        if (now < scrollIdleTime && now < maxWaitTime) {
            // A list is scrolling. The relayout waits, so it's done once for all the requests
            // made while scrolling, and not while the list calculates the layouts of new elements.
            Utils.runOnMainThreadDelayed(LithoRelayoutPatch::relayoutOutdatedViews,
                    Math.min(scrollIdleTime, maxWaitTime) - now);
            return;
        }
        relayoutScheduled.set(false);
        final boolean remountListViews = remountListViewsRequested.getAndSet(false);
        final List<View> requestedViews;
        synchronized (remountViewsRequested) {
            requestedViews = new ArrayList<>(remountViewsRequested);
            remountViewsRequested.clear();
        }

        try {
            // Found before the views are mounted again, as unmounting a Litho view unmounts its texts.
            // The outdated views mounted again are then laid out again by this relayout,
            // instead of a later relayout requested when their outdated texts are mounted again.
            Set<LithoViewInterface> relayoutViews = new LinkedHashSet<>();
            for (Drawable textDrawable : new ArrayList<>(relayoutTextDrawables)) {
                // The callback is the host, or null if the text was unmounted.
                if (!(textDrawable.getCallback() instanceof View host)
                        || !(textDrawable instanceof TextContent textContent)) {
                    relayoutTextDrawables.remove(textDrawable);
                    continue;
                }

                // Drawables are reused for other texts, so the current texts are checked.
                boolean showsRelayoutText = false;
                boolean showsOutdatedText = false;
                for (CharSequence text : textContent.getTextItems()) {
                    Boolean outdated = isOutdated(text, host);
                    if (outdated != null) {
                        showsRelayoutText = true;
                        showsOutdatedText |= outdated;
                    }
                }

                if (!showsRelayoutText) {
                    relayoutTextDrawables.remove(textDrawable);
                } else if (showsOutdatedText) {
                    // The closest Litho view calculates the layout of the host.
                    LithoViewInterface lithoView = findLithoView(host);
                    if (lithoView != null) {
                        // The drawable is kept, as Litho can update the text of the mounted drawable
                        // without mounting it again, such as with a text that is loading again.
                        // It's removed by a later relayout once it no longer shows a loading text.
                        relayoutViews.add(lithoView);
                    }
                }
            }

            // Unmounting a Litho view also unmounts its nested Litho views,
            // so the views are found before any is unmounted.
            // The views of the app in the background are mounted again too, as they are not bound
            // again when the app is opened again.
            Set<LithoViewInterface> remountViews = new LinkedHashSet<>();
            if (remountListViews) {
                for (View view : new ArrayList<>(lithoViews)) {
                    if (isShownOrInBackground(view) && view instanceof LithoViewInterface lithoView) {
                        remountViews.add(lithoView);
                    }
                }
            } else {
                // A view that is no longer shown loads its images again when it's bound again.
                for (View view : requestedViews) {
                    LithoViewInterface lithoView = isShownOrInBackground(view) ? findLithoView(view) : null;
                    if (lithoView != null) {
                        remountViews.add(lithoView);
                    }
                }
            }
            if (!remountViews.isEmpty()) {
                Logger.printDebug(() -> "Remounting Litho views: " + remountViews.size());
            }
            // A view mounted again by an earlier relayout can be requested again, such as for another image.
            remountedViews.clear();
            pendingRemountViews.addAll(remountViews);
            pendingRelayoutViews.addAll(relayoutViews);
            // Started by a frame callback, so the first view is laid out alone in the frame.
            if (!pendingViewsFrameCallbackPosted && (!pendingRelayoutViews.isEmpty() || !pendingRemountViews.isEmpty())) {
                pendingViewsFrameCallbackPosted = true;
                Choreographer.getInstance().postFrameCallback(pendingViewsFrameCallback);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "relayoutOutdatedViews failure", ex);
        }
    }

    /**
     * Lays out again, or mounts again, the next pending Litho view, and the next in the next frame.
     * Each layout is calculated on the main thread, so the layouts are calculated in different frames.
     */
    private static void processNextPendingView() {
        try {
            final boolean remount = !pendingRemountViews.isEmpty();
            Iterator<LithoViewInterface> iterator = (remount ? pendingRemountViews : pendingRelayoutViews).iterator();
            if (!iterator.hasNext()) {
                remountedViews.clear();
                return;
            }
            LithoViewInterface lithoView = iterator.next();
            iterator.remove();
            View view = (View) lithoView;
            if (remount && isShownOrInBackground(view) && !isRemountedMeanwhile(view)) {
                lithoView.patch_forceRemount();
                remountedViews.add(view);
            }
            // A recycled view that is no longer attached is laid out again when it's bound again.
            if ((!remount || pendingRelayoutViews.remove(lithoView)) && view.isAttachedToWindow()) {
                lithoView.patch_forceRelayout();
            }
            pendingViewsFrameCallbackPosted = true;
            Choreographer.getInstance().postFrameCallback(pendingViewsFrameCallback);
        } catch (Exception ex) {
            Logger.printException(() -> "processNextPendingView failure", ex);
        }
    }

    /**
     * @return If the view or a view that contains it was mounted again since the pending views
     *         started to be processed, which also mounted again the view.
     */
    private static boolean isRemountedMeanwhile(View view) {
        while (true) {
            if (remountedViews.contains(view)) {
                return true;
            }
            if (!(view.getParent() instanceof View parent)) {
                return false;
            }
            view = parent;
        }
    }

    /**
     * The window of a stopped activity is hidden, such as while the app is in the background,
     * and its views are shown again when the app is opened again. Must be called on the main thread.
     *
     * @return If the view is shown, or is hidden only because its window is hidden.
     */
    public static boolean isShownOrInBackground(View view) {
        if (view.isShown()) {
            return true;
        }
        if (!view.isAttachedToWindow()) {
            return false;
        }
        View current = view;
        while (current.getParent() instanceof View parent) {
            if (current.getVisibility() != View.VISIBLE) {
                return false;
            }
            current = parent;
        }
        // The root view is the decor view of the window.
        return current.getVisibility() != View.VISIBLE;
    }

    /**
     * @return The closest Litho view of the view, which calculates its layout and mounts it,
     *         or null if the view is not in a Litho view.
     */
    @Nullable
    private static LithoViewInterface findLithoView(View view) {
        while (!(view instanceof LithoViewInterface) && view.getParent() instanceof View parent) {
            view = parent;
        }
        return view instanceof LithoViewInterface lithoView ? lithoView : null;
    }

    /**
     * @param host The view that shows the text, or null if it is not known yet.
     * @return If any {@link RelayoutSpan} of the text is outdated and can lay out the host,
     *         or null if the text has none.
     */
    @Nullable
    private static Boolean isOutdated(@Nullable CharSequence text, @Nullable View host) {
        if (!(text instanceof Spanned spanned)) {
            return null;
        }
        RelayoutSpan[] spans = spanned.getSpans(0, spanned.length(), RelayoutSpan.class);
        if (spans.length == 0) {
            return null;
        }
        for (RelayoutSpan span : spans) {
            if (span.isOutdated() && (host == null || span.canRelayoutHost(host))) {
                return true;
            }
        }
        return false;
    }
}
