/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3384
 * https://github.com/MorpheApp/morphe-patches/pull/3447
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.shared.patches;

import android.graphics.drawable.Drawable;
import android.text.Spanned;
import android.view.View;

import androidx.annotation.Nullable;

import com.facebook.litho.TextContent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
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
 * as the image components do not change.
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
         * @return If the host of this text may be laid out again. A text drawable is registered
         *         globally, so a view recycled into another screen still holds it; returning false
         *         for such a view keeps its screen untouched.
         */
        default boolean canRelayoutHost(View host) {
            return host.isShown();
        }
    }

    /**
     * Relayouts requested at about the same time, such as titles fetched at the same time,
     * are done with a single pass.
     */
    private static final long RELAYOUT_DELAY_MILLISECONDS = 100;

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
     * Until when a text with a {@link RelayoutSpan} may be mounted. The hooks below run for every view
     * and text of the app, so they return immediately while this window is closed, which is the case
     * on every screen that never shows such a text.
     * <p>
     * A permanent flag would leave them scanning every text of the app after the chat was seen once,
     * which made entering and leaving fullscreen stutter.
     */
    private static volatile long relayoutSpansUntil;

    /** How long the hooks stay enabled after a span was registered or seen. */
    private static final long RELAYOUT_SPANS_WINDOW_MILLISECONDS = 10_000;

    /**
     * If a relayout is scheduled. Requests can be made on different threads at the same time,
     * such as titles fetched at the same time, so it's set atomically and a single relayout is scheduled.
     */
    private static final AtomicBoolean relayoutScheduled = new AtomicBoolean();

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
     * Must be called on the main thread.
     *
     * @return The {@link RelayoutSpan} of the class of the shown Litho texts,
     *         such as the texts that are on screen and not scrolled off-screen.
     */
    public static <T extends RelayoutSpan> Set<T> findShownSpans(Class<T> spanClass) {
        Set<T> shownSpans = new HashSet<>();
        for (Drawable textDrawable : snapshotTextDrawables()) {
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

    /**
     * Must be called on the main thread.
     *
     * @return The host view and the current text of every shown text drawable, so a caller can act
     *         on the view that displays a text, such as attaching a long press listener.
     */
    public static List<Object[]> findShownTexts() {
        List<Object[]> result = new ArrayList<>();
        for (Drawable textDrawable : snapshotTextDrawables()) {
            if (!(textDrawable.getCallback() instanceof View host) || !host.isShown()
                    || !(textDrawable instanceof TextContent textContent)) {
                continue;
            }
            for (CharSequence text : textContent.getTextItems()) {
                result.add(new Object[]{host, text});
            }
        }
        return result;
    }

    private static void scheduleRelayout() {
        if (relayoutScheduled.compareAndSet(false, true)) {
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
        // This runs for every Litho view the app measures, which is the whole feed while scrolling.
        // The set only exists so a remount can find the shown views, so nothing is stored while no
        // remount was requested.
        if (remountListViewsRequested.get() || lithoViewMeasuredListener != null) {
            lithoViews.add(lithoView);
        }
        Runnable listener = lithoViewMeasuredListener;
        if (listener != null) {
            listener.run();
        }
    }

    /**
     * Called by a patch that puts {@link RelayoutSpan}s on texts, so the hooks that look for them are
     * enabled for a while. Without this the hooks return immediately and cost nothing.
     */
    public static void onRelayoutSpanRegistered() {
        relayoutSpansUntil = System.currentTimeMillis() + RELAYOUT_SPANS_WINDOW_MILLISECONDS;
    }

    /** @return If a text with a {@link RelayoutSpan} may be mounted right now. */
    private static boolean areRelayoutSpansActive() {
        return System.currentTimeMillis() < relayoutSpansUntil;
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
            // Runs for every text the app mounts, so the span walk is skipped while no relayout is
            // pending: without a registered RelayoutSpan there is nothing to lay out again.
            if (!areRelayoutSpansActive()) {
                return;
            }
            Boolean outdated = isOutdated(text);
            if (outdated != null) {
                // The chat is still on screen, so the next text may carry a span as well.
                onRelayoutSpanRegistered();
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

    /**
     * @return The mounted text drawables. A WeakHashMap cannot be iterated while the map is being
     *         modified by another thread, and a copy is needed anyway, so the copy is made once here
     *         and skipped entirely while no text is registered.
     */
    private static List<Drawable> snapshotTextDrawables() {
        if (relayoutTextDrawables.isEmpty()) {
            return Collections.emptyList();
        }
        return new ArrayList<>(relayoutTextDrawables);
    }

    private static void relayoutOutdatedViews() {
        relayoutScheduled.set(false);
        final boolean remountListViews = remountListViewsRequested.getAndSet(false);

        try {
            // Found before the views are mounted again, as unmounting a Litho view unmounts its texts.
            // The outdated views mounted again are then laid out again by this relayout,
            // instead of a later relayout requested when their outdated texts are mounted again.
            Set<LithoViewInterface> relayoutViews = new LinkedHashSet<>();
            for (Drawable textDrawable : snapshotTextDrawables()) {
                // The callback is the host, or null if the text was unmounted.
                if (!(textDrawable.getCallback() instanceof View host)
                        || !(textDrawable instanceof TextContent textContent)) {
                    relayoutTextDrawables.remove(textDrawable);
                    continue;
                }
                // Drawables are reused for other texts, so the current texts are checked.
                boolean showsRelayoutText = false;
                boolean showsOutdatedText = false;
                boolean canRelayout = false;
                for (CharSequence text : textContent.getTextItems()) {
                    if (!(text instanceof Spanned spanned)) {
                        continue;
                    }
                    for (RelayoutSpan span : spanned.getSpans(0, spanned.length(), RelayoutSpan.class)) {
                        showsRelayoutText = true;
                        if (span.isOutdated()) {
                            showsOutdatedText = true;
                            canRelayout |= span.canRelayoutHost(host);
                        }
                    }
                }

                if (!showsRelayoutText) {
                    relayoutTextDrawables.remove(textDrawable);
                } else if (showsOutdatedText && canRelayout) {
                    // The closest Litho view calculates the layout of the host.
                    View view = host;
                    while (!(view instanceof LithoViewInterface) && view.getParent() instanceof View parent) {
                        view = parent;
                    }
                    if (view instanceof LithoViewInterface lithoView) {
                        // The drawable is kept, as Litho can update the text of the mounted drawable
                        // without mounting it again, such as with a text that is loading again.
                        // It's removed by a later relayout once it no longer shows a loading text.
                        relayoutViews.add(lithoView);
                    }
                }
            }

            if (remountListViews) {
                // Unmounting a Litho view also unmounts its nested Litho views,
                // so the views are found before any is unmounted.
                List<LithoViewInterface> remountViews = new ArrayList<>(lithoViews.size());
                for (View view : new ArrayList<>(lithoViews)) {
                    if (view.isShown() && view instanceof LithoViewInterface lithoView) {
                        remountViews.add(lithoView);
                    }
                }
                for (LithoViewInterface lithoView : remountViews) {
                    lithoView.patch_forceRemount();
                }
                Logger.printDebug(() -> "Remounted Litho views: " + remountViews.size());
            }

            for (LithoViewInterface lithoView : relayoutViews) {
                lithoView.patch_forceRelayout();
            }
        } catch (Exception ex) {
            Logger.printException(() -> "relayoutOutdatedViews failure", ex);
        }
    }

    /**
     * @return If any {@link RelayoutSpan} of the text is outdated, or null if the text has none.
     */
    @Nullable
    private static Boolean isOutdated(@Nullable CharSequence text) {
        if (!(text instanceof Spanned spanned)) {
            return null;
        }
        RelayoutSpan[] spans = spanned.getSpans(0, spanned.length(), RelayoutSpan.class);
        if (spans.length == 0) {
            return null;
        }
        for (RelayoutSpan span : spans) {
            if (span.isOutdated()) {
                return true;
            }
        }
        return false;
    }
}
