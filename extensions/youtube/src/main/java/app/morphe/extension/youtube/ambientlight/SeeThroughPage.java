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
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import androidx.annotation.Nullable;

import java.util.IdentityHashMap;
import java.util.Map;

import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;

/**
 * Makes the watch page transparent, so the light surfaces underneath the window show through.
 * Litho backgrounds change color in {@link AmbientLightController#getLithoColor}.
 * This class handles the backgrounds of regular views.
 */
final class SeeThroughPage {
    private SeeThroughPage() {
    }

    /** Scrims of the watch page that are not page colored. */
    private static final int[] SCRIM_IDS = {
            ResourceUtils.getIdentifier(ResourceType.ID, "watch_panel"),
            ResourceUtils.getIdentifier(ResourceType.ID, "watch_panel_scrim"),
            ResourceUtils.getIdentifier(ResourceType.ID, "video_info_loading_layout"),
    };
    private static final String[] SYSTEM_BAR_BACKGROUNDS = {
            "statusBarBackground", "navigationBarBackground"
    };
    private static final int MAX_DEPTH = 48;

    private static final class Saved {
        final Drawable drawable;
        final int alpha;

        Saved(Drawable drawable, int alpha) {
            this.drawable = drawable;
            this.alpha = alpha;
        }
    }

    private static final IdentityHashMap<View, Saved> cleared = new IdentityHashMap<>();
    @Nullable
    private static View host;
    @Nullable
    private static Drawable hostBackground;

    /**
     * Clears the page around {@code player}. Run again when the page has changed.
     */
    static void apply(View page, View player, int pageColor) {
        if (host != page) {
            restore();
            host = page;
            hostBackground = page.getBackground();
            page.setBackground(new PunchThroughDrawable());
        }
        cleared.keySet().removeIf(view -> !view.isAttachedToWindow());
        clearBackgrounds(page, player, page.getWidth() / 3, pageColor, 0);

        for (ViewParent parent = page.getParent(); parent instanceof View; parent = parent.getParent()) {
            clear((View) parent);
        }
        View root = page.getRootView();
        for (String name : SYSTEM_BAR_BACKGROUNDS) {
            final int id = root.getResources().getIdentifier(name, "id", "android");
            if (id != 0) clear(root.findViewById(id));
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
    }

    static void restore() {
        for (Map.Entry<View, Saved> entry : cleared.entrySet()) {
            entry.getValue().drawable.setAlpha(entry.getValue().alpha);
            entry.getKey().invalidate();
        }
        cleared.clear();
        if (host != null) {
            host.setBackground(hostBackground);
            host = null;
            hostBackground = null;
        }
    }

    private static void clearBackgrounds(View view, View player, int minWidth, int pageColor, int depth) {
        if (view == player || depth > MAX_DEPTH || view.getVisibility() != View.VISIBLE) return;

        Drawable background = view.getBackground();
        if (background != null && view.getWidth() >= minWidth && isPageBackground(view, background, pageColor)) {
            clear(view);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
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

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSPARENT;
        }
    }
}
