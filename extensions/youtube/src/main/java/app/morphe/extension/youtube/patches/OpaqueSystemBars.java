/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;

import androidx.annotation.ColorInt;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.theme.ThemeUtils;

/**
 * Paints the two views YouTube makes translucent, so the status bar and the navigation bar look solid.
 * <p>
 * The app has a feature flag for the same thing, but it also decides whether the window is drawn
 * edge to edge. Turning it off moves the whole layout by the height of the system bars, and
 * everything that measures against the window has to compensate for it, so the flag is left alone
 * and only the color is taken over.
 */
final class OpaqueSystemBars {

    /**
     * The bar of navigation buttons. Its background covers the system navigation buttons or
     * gesture handle as well, because the window is drawn edge to edge.
     */
    private static final int PIVOT_BAR = ResourceUtils.getIdentifier(
            ResourceType.ID, "pivot_bar");

    /**
     * The view the app dims the status bar with. It is empty and the app owns its height and
     * visibility, so only its color is taken over.
     */
    private static final int STATUS_BAR_VIEW = ResourceUtils.getIdentifier(
            ResourceType.ID, "global_status_bar_view");

    static void apply(View bottomBarContainer) {
        View pivotBar = bottomBarContainer.findViewById(PIVOT_BAR);
        if (pivotBar != null) {
            keepBackground(pivotBar, navigationBarBackground(pivotBar.getContext()));
        }

        View statusBarView = bottomBarContainer.getRootView().findViewById(STATUS_BAR_VIEW);
        if (statusBarView != null) {
            // Painted as the foreground, so the dimming the app keeps writing into
            // the background of this view never shows through again.
            statusBarView.setForeground(new ColorDrawable(ThemeUtils.getAppBackgroundColor()));
        }
    }

    /**
     * @return The background the app itself uses for a navigation bar that is not translucent,
     *         which is the app background under a separator along the top edge.
     */
    private static Drawable navigationBarBackground(Context context) {
        Drawable background = new ColorDrawable(ThemeUtils.getAppBackgroundColor());

        final int separatorHeight = ResourceUtils.getDimensionPixelSize("line_separator_height");
        final int separatorColor = separatorColor(context);
        if (separatorHeight <= 0 || Color.alpha(separatorColor) == 0) {
            return background;
        }

        LayerDrawable layers = new LayerDrawable(
                new Drawable[]{background, new ColorDrawable(separatorColor)});
        layers.setLayerHeight(1, separatorHeight);
        layers.setLayerGravity(1, Gravity.TOP);

        return layers;
    }

    /**
     * The app rebuilds the navigation bar on a configuration change and hands it the translucent
     * background again, so the paint is put back whenever that happens.
     */
    private static void keepBackground(View view, Drawable background) {
        view.setBackground(background);

        view.getViewTreeObserver().addOnPreDrawListener(() -> {
            if (view.getBackground() != background) {
                view.setBackground(background);
            }

            return true;
        });
    }

    /**
     * @return The separator color of the app theme, or transparent if the app no longer has one.
     */
    @ColorInt
    private static int separatorColor(Context context) {
        final int attribute = ResourceUtils.getIdentifier(ResourceType.ATTR, "ytSeparator");
        if (attribute == 0) {
            Logger.printException(() -> "Could not find attribute: ytSeparator");
            return Color.TRANSPARENT;
        }

        // The attribute points at a plain color, so a color state list is never handed back.
        TypedValue value = new TypedValue();
        if (!context.getTheme().resolveAttribute(attribute, value, true)
                || value.type < TypedValue.TYPE_FIRST_COLOR_INT
                || value.type > TypedValue.TYPE_LAST_COLOR_INT) {
            return Color.TRANSPARENT;
        }

        return value.data;
    }

    private OpaqueSystemBars() {
    }
}
