/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 */

package app.morphe.extension.youtube.patches;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.concurrent.atomic.AtomicBoolean;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public class SnackbarPatch {

    public static final AtomicBoolean PREPARING_LITHO_SNACKBAR = new AtomicBoolean(false);

    private static final int YT_BLACK_TEXT = 0xFF0F0F0F;
    private static final int YT_WHITE_TEXT = 0xFFF1F1F1;

    public static boolean hideSnackbar() {
        return Settings.HIDE_SNACKBAR.get();
    }

    public static void onLithoSnackbarPrepare() {
        if (Settings.CUSTOM_SNACKBAR_THEME.get()) {
            PREPARING_LITHO_SNACKBAR.set(true);
        }
    }

    public static void hideLithoSnackBar(FrameLayout frameLayout) {
        if (Settings.HIDE_SNACKBAR.get()) {
            Utils.hideViewByLayoutParams(frameLayout);
        }
    }

    /**
     * Injection point.
     */
    public static void handleLegacySnackbar(View view) {
        if (Settings.HIDE_SNACKBAR.get()) {
            Utils.hideViewByLayoutParams(view);
            view.setVisibility(View.GONE);
            return;
        }

        if (Settings.CUSTOM_SNACKBAR_THEME.get()) {
            applyCustomThemeToView(view);
        }
    }

    public static void setLithoSnackBarBackgroundColor(FrameLayout frameLayout, int originalColor) {
        if (!Settings.CUSTOM_SNACKBAR_THEME.get()) {
            frameLayout.setBackgroundColor(originalColor);
            return;
        }
        applyCustomThemeToView(frameLayout);
    }

    /**
     * Applies to both Litho FrameLayouts and Legacy ViewGroups.
     */
    private static void applyCustomThemeToView(View view) {
        try {
            GradientDrawable drawable = new GradientDrawable();

            boolean isDark = Utils.isDarkModeEnabled();
            String customColorString = isDark ? Settings.SNACKBAR_COLOR_DARK.get() : Settings.SNACKBAR_COLOR_LIGHT.get();

            int backgroundColor = Utils.isNotEmpty(customColorString)
                    ? Utils.getColorFromString(customColorString)
                    : Color.parseColor(isDark ? "#FF0F0F0F" : "#FFF1F1F1");

            drawable.setColor(backgroundColor);

            String radiusStr = Settings.SNACKBAR_CORNER_RADIUS.get();
            float radiusDp = Utils.isNotEmpty(radiusStr) ? Float.parseFloat(radiusStr) : 8.0f;
            drawable.setCornerRadius(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, radiusDp, Utils.getResources().getDisplayMetrics()));

            String strokeColorStr = Settings.SNACKBAR_STROKE_COLOR.get();
            if (Utils.isNotEmpty(strokeColorStr)) {
                int strokePx = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1.0f, Utils.getResources().getDisplayMetrics());
                drawable.setStroke(strokePx, Utils.getColorFromString(strokeColorStr));
            }

            view.setBackground(drawable);

            if (!(view instanceof FrameLayout)) {
                applyTextColorToTextViews(view, getCalculatedTextColor(backgroundColor), true);
            }

        } catch (Exception e) {
            Logger.printException(() -> "Failed to apply dynamic Snackbar theme", e);
        }
    }

    /**
     * Recursively searches legacy layouts to paint the TextViews AND strip nested backgrounds.
     */
    private static void applyTextColorToTextViews(View view, int textColor, boolean isRoot) {
        if (!isRoot && view.getBackground() != null) {
            view.setBackgroundColor(Color.TRANSPARENT);
        }

        if (view instanceof TextView) {
            ((TextView) view).setTextColor(textColor);
        } else if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                applyTextColorToTextViews(group.getChildAt(i), textColor, false);
            }
        }
    }

    private static int getCalculatedTextColor(int currentBackgroundColor) {
        String customTextString = Settings.SNACKBAR_TEXT_COLOR.get();
        if (Utils.isNotEmpty(customTextString)) {
            try {
                return Utils.getColorFromString(customTextString);
            } catch (Exception ignored) {}
        }

        double luminance = (0.299 * Color.red(currentBackgroundColor) + 0.587 * Color.green(currentBackgroundColor) + 0.114 * Color.blue(currentBackgroundColor)) / 255;
        return luminance > 0.5 ? Color.BLACK : Color.WHITE;
    }

    /**
     * Intercepts Litho text colors across thread boundaries.
     */
    public static int getLithoColor(int originalColor) {
        if (Settings.CUSTOM_SNACKBAR_THEME.get() && PREPARING_LITHO_SNACKBAR.get()) {
            if (originalColor == YT_BLACK_TEXT || originalColor == YT_WHITE_TEXT) {
                PREPARING_LITHO_SNACKBAR.set(false);

                boolean isDark = Utils.isDarkModeEnabled();
                String customColorString = isDark ? Settings.SNACKBAR_COLOR_DARK.get() : Settings.SNACKBAR_COLOR_LIGHT.get();
                int backgroundColor = Utils.isNotEmpty(customColorString)
                        ? Utils.getColorFromString(customColorString)
                        : Color.parseColor(isDark ? "#FF0F0F0F" : "#FFF1F1F1");

                return getCalculatedTextColor(backgroundColor);
            }
        }
        return originalColor;
    }
}