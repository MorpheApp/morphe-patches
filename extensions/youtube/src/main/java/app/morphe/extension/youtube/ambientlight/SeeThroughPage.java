/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4576
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.ambientlight;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.support.v7.widget.RecyclerView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.IdentityHashMap;
import java.util.Map;

import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;

/**
 * Makes the watch page transparent, so the light surfaces underneath the window show through.
 * Litho backgrounds change color in {@link AmbientLightController#getLithoColor}.
 * This class handles the backgrounds of regular views.
 */
@SuppressWarnings("unused")
final class SeeThroughPage {
    private SeeThroughPage() {
    }

    /** Scrims of the watch page that are not page colored. */
    private static final int[] SCRIM_IDS = {
            ResourceUtils.getIdentifier(ResourceType.ID, "watch_panel"),
            ResourceUtils.getIdentifier(ResourceType.ID, "watch_panel_scrim"),
            ResourceUtils.getIdentifier(ResourceType.ID, "video_info_loading_layout"),
    };
    private static final int[] SYSTEM_BAR_BACKGROUND_IDS = {
            android.R.id.statusBarBackground, android.R.id.navigationBarBackground
    };
    private static final int MAX_DEPTH = 48;
    /** Holds the list of the watch page and the headers that stick to its top, such as the filter chips. */
    private static final int STICKY_HEADER_CONTAINER_ID =
            ResourceUtils.getIdentifier(ResourceType.ID, "sticky_header_container");

    private record Saved(Drawable drawable, int alpha) {
    }

    private static final IdentityHashMap<View, Saved> cleared = new IdentityHashMap<>();
    /** The page whose background was replaced. Weak, so a closed page is not kept. */
    private static WeakReference<View> hostRef = new WeakReference<>(null);
    @Nullable
    private static Drawable hostBackground;
    private static WeakReference<ViewGroup> stickyHeaderContainerRef = new WeakReference<>(null);
    private static WeakReference<View> clippedListRef = new WeakReference<>(null);
    private static final Rect listClipBounds = new Rect();

    /**
     * Clears the page around {@code player}. Run again when the page has changed.
     */
    static void apply(View page, View player, int pageColor) {
        if (hostRef.get() != page) {
            restore();
            hostRef = new WeakReference<>(page);
            hostBackground = page.getBackground();
            page.setBackground(new PunchThroughDrawable());
        }
        cleared.keySet().removeIf(view -> !view.isAttachedToWindow());
        clearBackgrounds(page, player, page.getWidth() / 3, pageColor, 0);

        if (STICKY_HEADER_CONTAINER_ID != 0 && page.findViewById(STICKY_HEADER_CONTAINER_ID) instanceof ViewGroup container
                && stickyHeaderContainerRef.get() != container) {
            stickyHeaderContainerRef = new WeakReference<>(container);
        }

        for (ViewParent parent = page.getParent(); parent instanceof View; parent = parent.getParent()) {
            clear((View) parent);
        }
        View root = page.getRootView();
        for (int id : SYSTEM_BAR_BACKGROUND_IDS) {
            clear(root.findViewById(id));
        }
    }

    /**
     * YouTube animates some of the backgrounds while the page scrolls, which undoes the clear.
     */
    static void keep() {
        for (Map.Entry<View, Saved> entry : cleared.entrySet()) {
            Drawable drawable = entry.getValue().drawable;
            if (entry.getKey().getBackground() == drawable && drawable.getAlpha() != 0) {
                drawable.setAlpha(0);
            }
        }
        // The headers that stick to the top of the list, such as the filter chips, are see-through too,
        // so the list is not drawn below them.
        ViewGroup container = stickyHeaderContainerRef.get();
        if (container == null || !container.isAttachedToWindow()) {
            setListClip(null, 0);
            return;
        }

        View list = null;
        for (int i = 0; i < container.getChildCount(); i++) {
            View child = container.getChildAt(i);
            if (child instanceof RecyclerView) {
                list = child;
                break;
            }
        }
        if (list == null) {
            setListClip(null, 0);
            return;
        }

        final float listTop = list.getY();
        float headersBottom = listTop;
        for (int i = 0; i < container.getChildCount(); i++) {
            View header = container.getChildAt(i);
            if (header == list || header.getVisibility() != View.VISIBLE || header.getHeight() == 0) continue;
            final float top = header.getY();
            // Only a header that sticks to the top covers the list. A header that scrolls
            // with the list is drawn over its own empty item.
            if (top <= listTop + 1 && top + header.getHeight() > headersBottom) {
                headersBottom = top + header.getHeight();
            }
        }
        setListClip(list, (int) (headersBottom - listTop));
    }

    /**
     * Runs for every frame, so nothing is allocated while the clip stays the same.
     */
    private static void setListClip(@Nullable View list, int top) {
        View clippedList = clippedListRef.get();
        if (clippedList != null && (clippedList != list || top <= 0)) {
            clippedList.setClipBounds(null);
            clippedListRef = new WeakReference<>(null);
        }
        if (list == null || top <= 0) return;

        if (!list.getClipBounds(listClipBounds) || listClipBounds.top != top
                || listClipBounds.right != list.getWidth() || listClipBounds.bottom != list.getHeight()) {
            list.setClipBounds(new Rect(0, top, list.getWidth(), list.getHeight()));
        }
        if (clippedList != list) {
            clippedListRef = new WeakReference<>(list);
        }
    }

    static void restore() {
        for (Map.Entry<View, Saved> entry : cleared.entrySet()) {
            entry.getValue().drawable.setAlpha(entry.getValue().alpha);
            entry.getKey().invalidate();
        }
        cleared.clear();
        setListClip(null, 0);
        stickyHeaderContainerRef = new WeakReference<>(null);
        View host = hostRef.get();
        if (host != null) {
            host.setBackground(hostBackground);
        }
        hostRef = new WeakReference<>(null);
        hostBackground = null;
    }

    private static void clearBackgrounds(View view, View player, int minWidth, int pageColor, int depth) {
        if (view == player || depth > MAX_DEPTH || view.getVisibility() != View.VISIBLE) return;

        Drawable background = view.getBackground();
        if (background != null && view.getWidth() >= minWidth && isPageBackground(view, background, pageColor)) {
            clear(view);
        }
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                clearBackgrounds(group.getChildAt(i), player, minWidth, pageColor, depth + 1);
            }
        }
    }

    private static boolean isPageBackground(View view, Drawable background, int pageColor) {
        if (background instanceof PunchThroughDrawable) return false;
        if (background instanceof ColorDrawable
                && AmbientLightMath.seeThroughColor(((ColorDrawable) background).getColor(), pageColor) == 0) {
            return true;
        }
        for (int id : SCRIM_IDS) {
            if (id != 0 && view.getId() == id) return true;
        }
        return false;
    }

    private static void clear(@Nullable View view) {
        Drawable background = view == null ? null : view.getBackground();
        if (background == null) return;

        Saved saved = cleared.get(view);
        if (saved != null && saved.drawable == background) return;

        Drawable mutable = background.mutate();
        cleared.put(view, new Saved(mutable, mutable.getAlpha()));
        mutable.setAlpha(0);
        view.invalidate();
    }

    /**
     * Removes what was drawn below the page, such as the home feed.
     */
    private static final class PunchThroughDrawable extends Drawable {
        @Override
        public void draw(Canvas canvas) {
            canvas.drawColor(0, PorterDuff.Mode.CLEAR);
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {
        }

        @Deprecated
        @Override
        public int getOpacity() {
            return PixelFormat.TRANSPARENT;
        }
    }
}
