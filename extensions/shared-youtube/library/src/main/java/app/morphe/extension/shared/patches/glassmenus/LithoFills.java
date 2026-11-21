/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5107
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.shared.patches.glassmenus;

import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.view.View;

import androidx.annotation.ColorInt;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The opaque fills Litho draws, such as the background of a bottom sheet. These are drawn by the Litho host view
 * they are mounted in, so they are not the background of a view. The Litho color hook passes on every Litho
 * background with the color it is drawn with, and the opaque ones are kept here until they are no longer used.
 */
final class LithoFills {

    /**
     * A fill and the view it is mounted in.
     */
    record Fill(Drawable drawable, @ColorInt int color, View host) {
    }

    private static final Map<Drawable, Integer> colors = new WeakHashMap<>();

    static void onColor(Drawable drawable, @ColorInt int color) {
        synchronized (colors) {
            if (Color.alpha(color) >= 0xF0) {
                colors.put(drawable, color);
            } else {
                colors.remove(drawable);
            }
        }
    }

    /**
     * @return The color the drawable is drawn with, if it is an opaque Litho fill.
     */
    @Nullable
    static Integer colorOf(Drawable drawable) {
        synchronized (colors) {
            return colors.get(drawable);
        }
    }

    /**
     * @return The fills that are mounted in the view or in a view inside it.
     */
    static List<Fill> fillsInside(View view) {
        List<Fill> fills = new ArrayList<>();
        synchronized (colors) {
            for (Map.Entry<Drawable, Integer> entry : colors.entrySet()) {
                Drawable drawable = entry.getKey();
                if (drawable == null) continue;
                View host = hostOf(drawable);
                if (host != null && isInside(host, view)) {
                    fills.add(new Fill(drawable, entry.getValue(), host));
                }
            }
        }
        return fills;
    }

    /**
     * @return The view that draws the drawable. Litho can mount it in a wrapper drawable.
     */
    @Nullable
    static View hostOf(Drawable drawable) {
        Drawable.Callback callback = drawable.getCallback();
        while (callback instanceof Drawable parent) {
            callback = parent.getCallback();
        }
        return callback instanceof View view ? view : null;
    }

    static boolean isInside(View child, View view) {
        for (Object current = child; current instanceof View currentView; current = currentView.getParent()) {
            if (currentView == view) return true;
        }
        return false;
    }

    private LithoFills() {
    }
}
