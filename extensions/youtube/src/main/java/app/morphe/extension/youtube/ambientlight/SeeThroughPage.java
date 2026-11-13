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
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
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
    /**
     * Holds the content of the page below the player. While it fades in, as it does when the
     * player leaves fullscreen, YouTube draws black under it.
     */
    private static final int PAGE_CONTENT_ID = ResourceUtils.getIdentifier(ResourceType.ID, "video_metadata_layout");
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
    private static WeakReference<View> pageContentRef = new WeakReference<>(null);
    @Nullable
    private static Drawable pageContentBackground;
    /** If the content of the page is fading, which is when YouTube draws black under it. */
    private static boolean pageContentFading;
    private static boolean pageContentVisible = true;
    private static volatile float pageContentAlpha = 1f;
    private static float pageLightAlpha = 1f;
    private static final List<WeakReference<View>> systemBarBackgrounds = new ArrayList<>(2);
    private static WeakReference<View> clippedListRef = new WeakReference<>(null);
    private static final Rect hostLightBounds = new Rect();
    private static final Rect contentLightBounds = new Rect();
    private static final Rect listClipBounds = new Rect();

    /**
     * Clears the page around {@code player}. Run again when the page has changed.
     */
    static void apply(View page, View player, int pageColor) {
        if (hostRef.get() != page) {
            restore();
            hostRef = new WeakReference<>(page);
            hostBackground = page.getBackground();
            page.setBackground(new LightDrawable(page));
        }
        cleared.keySet().removeIf(view -> !view.isAttachedToWindow());
        clearBackgrounds(page, player, page.getWidth() / 3, pageColor, 0);
        lightPageContent(page);

        if (STICKY_HEADER_CONTAINER_ID != 0 && page.findViewById(STICKY_HEADER_CONTAINER_ID) instanceof ViewGroup container
                && stickyHeaderContainerRef.get() != container) {
            stickyHeaderContainerRef = new WeakReference<>(container);
        }

        for (ViewParent parent = page.getParent(); parent instanceof View; parent = parent.getParent()) {
            clear((View) parent);
        }
        View root = page.getRootView();
        systemBarBackgrounds.clear();
        for (int id : SYSTEM_BAR_BACKGROUND_IDS) {
            View bar = root.findViewById(id);
            clear(bar);
            if (bar != null) systemBarBackgrounds.add(new WeakReference<>(bar));
        }
    }

    private static void lightPageContent(View page) {
        View content = PAGE_CONTENT_ID == 0 ? null : page.findViewById(PAGE_CONTENT_ID);
        if (content == null || content.getBackground() instanceof LightDrawable) return;

        restorePageContent();
        pageContentRef = new WeakReference<>(content);
        pageContentBackground = content.getBackground();
        content.setBackground(new LightDrawable(content));
    }

    private static void restorePageContent() {
        View content = pageContentRef.get();
        if (content != null) content.setBackground(pageContentBackground);
        pageContentRef = new WeakReference<>(null);
        pageContentBackground = null;
    }

    static boolean isPageContentFading() {
        return pageContentFading;
    }

    /**
     * @return How much of the page the light covers, from 0 to 1.
     */
    static float lightAlpha() {
        return pageLightAlpha;
    }

    /**
     * Main thread.
     *
     * @return The part of the window that the page fills with the light by itself, or null.
     *         While the page fades, the page under it shows there, and hides what is under both.
     */
    @Nullable
    static Rect lightBounds() {
        return lightBounds(hostRef.get(), null, hostLightBounds);
    }

    /**
     * @return The part of the window that a view fills with the light, with nothing of what is
     *         under it showing through, or null. The view is looked at up to an ancestor.
     */
    @Nullable
    private static Rect lightBounds(@Nullable View view, @Nullable View ancestor, Rect bounds) {
        if (view == null || !(view.getBackground() instanceof LightDrawable) || !isDrawnPlain(view, ancestor)) {
            return null;
        }
        return view.getGlobalVisibleRect(bounds) ? bounds : null;
    }

    /**
     * @return If a view is shown where it is laid out, with nothing of what is under it
     *         showing through. The view is looked at up to an ancestor.
     */
    static boolean isDrawnPlain(View view, @Nullable View ancestor) {
        if (!view.isShown()) return false;

        for (View current = view; current != null && current != ancestor; ) {
            if (current.getAlpha() != 1f || current.getScaleX() != 1f || current.getScaleY() != 1f
                    || current.getRotation() != 0f) {
                return false;
            }
            current = current.getParent() instanceof View parent ? parent : null;
        }
        return true;
    }

    /**
     * Any thread.
     *
     * @return How much of the content of the page shows, from 0 to 1. It is hidden in fullscreen.
     */
    static float contentAlpha() {
        return pageContentAlpha;
    }

    /**
     * Main thread. The views of the page draw the light again.
     */
    static void invalidateLight() {
        View host = hostRef.get();
        if (host != null) host.invalidate();
        View content = pageContentRef.get();
        if (content != null) content.invalidate();
    }

    /**
     * Main thread. A view draws the light for its place in the window, so a view that has moved
     * draws it again.
     */
    static void invalidateMovedLight() {
        invalidateIfMoved(hostRef.get());
        invalidateIfMoved(pageContentRef.get());
    }

    private static void invalidateIfMoved(@Nullable View view) {
        if (view != null && view.getBackground() instanceof LightDrawable light) light.invalidateIfMoved();
    }

    /**
     * YouTube animates some of the backgrounds while the page scrolls, which undoes the clear.
     *
     * @return If the page must be cleared again.
     */
    static boolean keep() {
        // The system gives the bars another background when the player enters or leaves fullscreen.
        for (WeakReference<View> bar : systemBarBackgrounds) {
            clear(bar.get());
        }
        View content = pageContentRef.get();
        final boolean contentVisible = content == null || content.getVisibility() == View.VISIBLE;
        // The content is hidden while the player is in the miniplayer, and hidden views are not cleared.
        final boolean contentAppeared = contentVisible && !pageContentVisible;
        pageContentVisible = contentVisible;
        final float contentAlpha = !contentVisible ? 0f : content == null ? 1f : content.getAlpha();
        pageContentFading = contentAlpha != pageContentAlpha;
        pageContentAlpha = contentAlpha;
        // On its way to the miniplayer, YouTube fades the content of the page out, and the page
        // under it comes into view. The light goes with the content.
        final float alpha = AmbientLightController.isSlidingToMiniplayer() ? contentAlpha : 1f;
        if (alpha != pageLightAlpha) {
            pageLightAlpha = alpha;
            AmbientLightController.invalidateWindowLight();
        }
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
            return contentAppeared;
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
            return contentAppeared;
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
        return contentAppeared;
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
        systemBarBackgrounds.clear();
        pageLightAlpha = 1f;
        pageContentAlpha = 1f;
        pageContentFading = false;
        pageContentVisible = true;
        restorePageContent();
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
        if (background != null && isPageBackground(view, background, minWidth, pageColor)) {
            clear(view);
        }
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                clearBackgrounds(group.getChildAt(i), player, minWidth, pageColor, depth + 1);
            }
        }
    }

    /**
     * @param minWidth A page colored background that is narrower belongs to a small view, and stays.
     */
    private static boolean isPageBackground(View view, Drawable background, int minWidth, int pageColor) {
        if (background instanceof LightDrawable) return false;
        // The second column of the watch page of a tablet is narrower than a third of the page.
        for (int id : SCRIM_IDS) {
            if (id != 0 && view.getId() == id) return true;
        }
        return view.getWidth() >= minWidth && background instanceof ColorDrawable
                && AmbientLightMath.seeThroughColor(((ColorDrawable) background).getColor(), pageColor) == 0;
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
     * Shows the light in a view. While the surfaces show the light, it removes what was drawn
     * below the view, such as the home feed below the page. While the player moves, it draws
     * the light itself. The page has it as its background, and so has the content of the page,
     * as YouTube draws black under the content while the page changes.
     */
    private static final class LightDrawable extends Drawable {
        private final View view;
        private final int[] location = new int[2];
        private int drawnLeft;
        private int drawnTop;

        LightDrawable(View view) {
            this.view = view;
        }

        void invalidateIfMoved() {
            view.getLocationInWindow(location);
            if (location[0] != drawnLeft || location[1] != drawnTop) view.invalidate();
        }

        @Override
        public void draw(Canvas canvas) {
            view.getLocationInWindow(location);
            drawnLeft = location[0];
            drawnTop = location[1];
            View content = pageContentRef.get();
            // The content fades by itself, with its light. Where it shows all of its own light,
            // the page under it does not draw.
            final float alpha = view == content ? 1f : pageLightAlpha;
            Rect covered = view == content ? null : lightBounds(content, view, contentLightBounds);
            if (!AmbientLightController.drawWindowLight(canvas, view, true, alpha, covered)) {
                canvas.drawColor(0, PorterDuff.Mode.CLEAR);
            }
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
            return PixelFormat.TRANSLUCENT;
        }
    }
}
