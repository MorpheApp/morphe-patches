/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3384
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.shared.patches;

import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;

import com.facebook.litho.ComponentHost;
import com.facebook.litho.TextContent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Predicate;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * Forces Litho views to calculate their layout again, which loads their texts again.
 * Litho otherwise keeps the calculated layout until the view is bound again,
 * such as after scrolling the view off screen and back.
 * <p>
 * Texts can be mounted after the views are checked, such as texts scrolled into view,
 * and views can be detached and attached again without mounting the texts again.
 * So the texts are also checked when mounted, and the views when attached.
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
    }

    /**
     * Filters of the texts to lay out again. Accessed only on the main thread.
     */
    private static final Set<Predicate<CharSequence>> textFilters = new LinkedHashSet<>();

    /**
     * Litho views attached to any window. Accessed only on the main thread.
     */
    private static final Set<View> attachedLithoViews = Collections.newSetFromMap(new WeakHashMap<>());

    /**
     * Litho views to check for texts to lay out again. Accessed only on the main thread.
     */
    private static final Set<View> pendingLithoViews = new LinkedHashSet<>();

    /**
     * Mounted text drawables with texts to lay out again. Accessed only on the main thread.
     */
    private static final List<Drawable> pendingTextDrawables = new ArrayList<>();

    private static boolean checkScheduled;

    private LithoRelayoutPatch() {
    }

    /**
     * Forces the Litho views that show a text matching the filter to calculate the layout again.
     * The filter is also used for the texts mounted later, so it must match
     * only the texts that are outdated. Requests made at the same time are done
     * with a single pass over the views. Can be called on any thread.
     *
     * @param textFilter Filter of the texts. The same instance should be used for the same texts,
     *                   so the filter is used only once.
     */
    public static void relayoutViewsShowingText(Predicate<CharSequence> textFilter) {
        Utils.runOnMainThread(() -> {
            textFilters.add(textFilter);
            pendingLithoViews.addAll(attachedLithoViews);
            scheduleCheck();
        });
    }

    /**
     * Injection point.
     */
    public static void onLithoViewAttached(View view) {
        try {
            attachedLithoViews.add(view);
            checkLater(view);
        } catch (Exception ex) {
            Logger.printException(() -> "onLithoViewAttached failure", ex);
        }
    }

    /**
     * Injection point.
     */
    public static void onLithoViewDetached(View view) {
        attachedLithoViews.remove(view);
    }

    /**
     * Injection point.
     * <p>
     * Called before the text drawable is added to its host, so the host is found later.
     */
    public static void onLithoTextMounted(Drawable textDrawable, CharSequence text) {
        try {
            if (text != null && matchesTextFilters(text)) {
                pendingTextDrawables.add(textDrawable);
                scheduleCheck();
            }
        } catch (Exception ex) {
            Logger.printException(() -> "onLithoTextMounted failure", ex);
        }
    }

    private static void checkLater(View view) {
        if (textFilters.isEmpty()) {
            return;
        }
        pendingLithoViews.add(view);
        scheduleCheck();
    }

    private static void scheduleCheck() {
        if (!checkScheduled) {
            checkScheduled = true;
            // Posted, so the layouts calculated in the current frame are mounted.
            Utils.runOnMainThread(LithoRelayoutPatch::checkPendingViews);
        }
    }

    private static void checkPendingViews() {
        checkScheduled = false;
        List<View> lithoViews = new ArrayList<>(pendingLithoViews);
        pendingLithoViews.clear();
        List<Drawable> textDrawables = new ArrayList<>(pendingTextDrawables);
        pendingTextDrawables.clear();

        try {
            Set<LithoViewInterface> relayoutViews = new LinkedHashSet<>();
            for (View lithoView : lithoViews) {
                if (lithoView.isAttachedToWindow() && showsText(lithoView)) {
                    relayoutViews.add((LithoViewInterface) lithoView);
                }
            }
            for (Drawable textDrawable : textDrawables) {
                // The callback is the host, or null if the text was unmounted.
                if (textDrawable.getCallback() instanceof View host) {
                    LithoViewInterface lithoView = findLithoView(host);
                    if (lithoView != null) {
                        relayoutViews.add(lithoView);
                    }
                }
            }
            for (LithoViewInterface lithoView : relayoutViews) {
                lithoView.patch_forceRelayout();
            }
        } catch (Exception ex) {
            Logger.printException(() -> "checkPendingViews failure", ex);
        }
    }

    /**
     * @return If a host laid out by the Litho view shows a text matching the filters.
     *         Nested Litho views calculate their own layout, and are checked separately.
     */
    private static boolean showsText(View view) {
        if (view instanceof ComponentHost host && hostShowsText(host)) {
            return true;
        }

        if (view instanceof ViewGroup group) {
            for (int i = 0, count = group.getChildCount(); i < count; i++) {
                View child = group.getChildAt(i);
                if (!(child instanceof LithoViewInterface) && showsText(child)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * @return The closest Litho view, which calculates the layout of the host.
     */
    @Nullable
    private static LithoViewInterface findLithoView(View host) {
        View view = host;
        while (!(view instanceof LithoViewInterface)) {
            if (!(view.getParent() instanceof View parent)) {
                return null;
            }
            view = parent;
        }
        return (LithoViewInterface) view;
    }

    private static boolean hostShowsText(ComponentHost host) {
        TextContent textContent = host.getTextContent();
        if (textContent == null) {
            return false;
        }
        for (CharSequence text : textContent.getTextItems()) {
            if (text != null && matchesTextFilters(text)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesTextFilters(CharSequence text) {
        for (Predicate<CharSequence> textFilter : textFilters) {
            if (textFilter.test(text)) {
                return true;
            }
        }
        return false;
    }
}
