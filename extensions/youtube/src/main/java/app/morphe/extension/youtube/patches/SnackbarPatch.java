/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;

import java.util.concurrent.atomic.AtomicBoolean;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public class SnackbarPatch {

    private static final AtomicBoolean LITHO_SNACKBAR_ACTIVE = new AtomicBoolean(false);

    private static final int YT_BLACK_TEXT = 0xFF0F0F0F;
    private static final int YT_WHITE_TEXT = 0xFFF1F1F1;

    private static class MorpheSnackbarDrawable extends GradientDrawable {}

    public static boolean hideSnackbar() {
        return Settings.HIDE_SNACKBAR.get();
    }

    public static void onLithoSnackbarPrepare() {
        if (Settings.CUSTOM_SNACKBAR_THEME.get()) {
            LITHO_SNACKBAR_ACTIVE.set(true);
        }
    }

    public static void hideLithoSnackBar(FrameLayout frameLayout) {
        if (Settings.HIDE_SNACKBAR.get()) {
            Utils.hideViewByLayoutParams(frameLayout);
        }
    }

    public static void handleLegacySnackbar(View view) {
        if (Settings.HIDE_SNACKBAR.get()) {
            Utils.hideViewByLayoutParams(view);
            view.setVisibility(View.GONE);
            return;
        }

        if (Settings.CUSTOM_SNACKBAR_THEME.get()) {
            enforceCustomTheme(view);

            view.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(@NonNull View v) {
                    enforceCustomTheme(v);
                }

                @Override
                public void onViewDetachedFromWindow(@NonNull View v) {}
            });
        }
    }

    public static void setLithoSnackBarBackgroundColor(FrameLayout frameLayout, int originalColor) {
        LITHO_SNACKBAR_ACTIVE.set(false);

        if (!Settings.CUSTOM_SNACKBAR_THEME.get()) {
            frameLayout.setBackgroundColor(originalColor);
            return;
        }

        enforceCustomTheme(frameLayout);
    }

    private static void enforceCustomTheme(View view) {
        applyCustomThemeToView(view);

        view.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                if (Settings.CUSTOM_SNACKBAR_THEME.get()) {
                    if (!(view.getBackground() instanceof MorpheSnackbarDrawable)) {
                        applyCustomThemeToView(view);
                    }
                } else {
                    view.getViewTreeObserver().removeOnPreDrawListener(this);
                }
                return true;
            }
        });
    }

    private static void applyCustomThemeToView(View view) {
        try {
            MorpheSnackbarDrawable drawable = new MorpheSnackbarDrawable();

            boolean isDark = Utils.isDarkModeEnabled();
            String customColorString = isDark ? Settings.CUSTOM_SNACKBAR_COLOR_DARK.get() : Settings.CUSTOM_SNACKBAR_COLOR_LIGHT.get();

            int backgroundColor = Utils.isNotEmpty(customColorString)
                    ? Color.parseColor(customColorString)
                    : Color.parseColor(isDark ? "#FF0F0F0F" : "#FFF1F1F1");

            drawable.setColor(backgroundColor);

            String radiusStr = Settings.CUSTOM_SNACKBAR_CORNER_RADIUS.get();
            float radiusDp = Utils.isNotEmpty(radiusStr) ? Float.parseFloat(radiusStr) : 8.0f;
            drawable.setCornerRadius(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, radiusDp, Utils.getResources().getDisplayMetrics()));

            String strokeColorStr = Settings.CUSTOM_SNACKBAR_STROKE_COLOR.get();
            if (Utils.isNotEmpty(strokeColorStr)) {
                int strokePx = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1.0f, Utils.getResources().getDisplayMetrics());
                drawable.setStroke(strokePx, Color.parseColor(strokeColorStr));
            }

            view.setBackground(drawable);
            view.setClipToOutline(true);

            if (!(view instanceof FrameLayout)) {
                applyTextColorToTextViews(view, getCalculatedTextColor(backgroundColor), 0);
            }

        } catch (Exception e) {
            Logger.printException(() -> "Failed to apply dynamic Snackbar theme", e);
        }
    }

    private static void applyTextColorToTextViews(View view, int textColor, int depth) {
        if (depth > 5) return;

        if (depth > 0 && view.getBackground() != null && !(view instanceof Button)) {
            view.setBackgroundColor(Color.TRANSPARENT);
        }

        if (view instanceof TextView) {
            if (!(view instanceof Button)) {
                ((TextView) view).setTextColor(textColor);
            }
        } else if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                applyTextColorToTextViews(group.getChildAt(i), textColor, depth + 1);
            }
        }
    }

    private static int getCalculatedTextColor(int currentBackgroundColor) {
        String customTextString = Settings.CUSTOM_SNACKBAR_TEXT_COLOR.get();
        if (Utils.isNotEmpty(customTextString)) {
            try {
                return Color.parseColor(customTextString);
            } catch (Exception ignored) {}
        }

        double luminance = (0.299 * Color.red(currentBackgroundColor) + 0.587 * Color.green(currentBackgroundColor) + 0.114 * Color.blue(currentBackgroundColor)) / 255;
        return luminance > 0.5 ? Color.BLACK : Color.WHITE;
    }

    public static int getLithoColor(int originalColor) {
        if (!Settings.CUSTOM_SNACKBAR_THEME.get()) return originalColor;
        if (originalColor == YT_BLACK_TEXT || originalColor == YT_WHITE_TEXT ||
                originalColor == 0xFF000000 || originalColor == 0xFFFFFFFF) {

            boolean isDark = Utils.isDarkModeEnabled();
            String customColorString = isDark ? Settings.CUSTOM_SNACKBAR_COLOR_DARK.get() : Settings.CUSTOM_SNACKBAR_COLOR_LIGHT.get();

            int backgroundColor = Utils.isNotEmpty(customColorString)
                    ? Color.parseColor(customColorString)
                    : Color.parseColor(isDark ? "#FF0F0F0F" : "#FFF1F1F1");

            return getCalculatedTextColor(backgroundColor);
        }

        return originalColor;
    }
}