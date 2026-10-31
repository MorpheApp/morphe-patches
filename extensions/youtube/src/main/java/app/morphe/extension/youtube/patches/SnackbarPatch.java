/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Objects;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public final class SnackbarPatch {
    private static final int YT_BLACK_TEXT = 0xFF0F0F0F;
    private static final int YT_WHITE_BACKGROUND = 0xFFF1F1F1;

    private SnackbarPatch() {}

    public static boolean isHideSnackbar() {
        return Settings.HIDE_SNACKBAR.get();
    }

    public static boolean isCustomSnackbarThemeEnabled() {
        return !isHideSnackbar() && Settings.CUSTOM_SNACKBAR_THEME.get();
    }

    public static boolean isInvertSnackbarThemeEnabled() {
        return !isHideSnackbar() && Settings.INVERT_SNACKBAR_THEME.get();
    }

    public static boolean hideSnackbar() {
        return isHideSnackbar();
    }

    public static void setLithoSnackBarBackgroundColor(@Nullable FrameLayout frameLayout, int originalColor) {
        if (frameLayout == null) return;

        DisplayMetrics metrics = frameLayout.getResources() != null
                ? frameLayout.getResources().getDisplayMetrics()
                : Resources.getSystem().getDisplayMetrics();
        float radiusPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, getCornerRadiusDp(), metrics);

        if (isCustomSnackbarThemeEnabled()) {
            try {
                applyCustomThemeToView(frameLayout);
                frameLayout.post(() -> applyCustomThemeToView(frameLayout));
            } catch (Exception e) {
                Logger.printException(() -> "setLithoSnackBarBackgroundColor failure", e);
                frameLayout.setBackgroundColor(originalColor);
                applyCornerRadius(frameLayout, radiusPx);
            }
        } else if (isInvertSnackbarThemeEnabled()) {
            try {
                applyInvertedThemeToLegacyView(frameLayout);
                frameLayout.post(() -> applyInvertedThemeToLegacyView(frameLayout));
            } catch (Exception e) {
                Logger.printException(() -> "setLithoSnackBarBackgroundColor failure", e);
                frameLayout.setBackgroundColor(originalColor);
                applyCornerRadius(frameLayout, radiusPx);
            }
        } else {
            frameLayout.setBackgroundColor(originalColor);
            applyCornerRadius(frameLayout, radiusPx);
            frameLayout.post(() -> applyCornerRadius(frameLayout, radiusPx));
        }
    }

    public static void handleLegacySnackbar(@Nullable View view) {
        if (view == null) return;
        try {
            if (isHideSnackbar()) {
                Utils.hideViewByLayoutParams(view);
                view.setVisibility(View.GONE);
                return;
            }

            if (isCustomSnackbarThemeEnabled()) {
                applyCustomThemeToView(view);
                view.post(() -> applyCustomThemeToView(view));

                view.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                    @Override
                    public void onViewAttachedToWindow(@NonNull View v) {
                        applyCustomThemeToView(v);
                    }

                    @Override
                    public void onViewDetachedFromWindow(@NonNull View v) {}
                });
            } else if (isInvertSnackbarThemeEnabled()) {
                applyInvertedThemeToLegacyView(view);
                view.post(() -> applyInvertedThemeToLegacyView(view));

                view.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                    @Override
                    public void onViewAttachedToWindow(@NonNull View v) {
                        applyInvertedThemeToLegacyView(v);
                    }

                    @Override
                    public void onViewDetachedFromWindow(@NonNull View v) {}
                });
            } else {
                DisplayMetrics metrics = view.getResources() != null
                        ? view.getResources().getDisplayMetrics()
                        : Resources.getSystem().getDisplayMetrics();
                float radiusPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, getCornerRadiusDp(), metrics);
                applyCornerRadius(view, radiusPx);
                view.post(() -> applyCornerRadius(view, radiusPx));
            }
        } catch (Exception e) {
            Logger.printException(() -> "handleLegacySnackbar failure", e);
        }
    }

    private static float getCornerRadiusDp() {
        String radiusStr = Settings.CUSTOM_SNACKBAR_CORNER_RADIUS.get();
        if (Utils.isNotEmpty(radiusStr)) {
            try {
                return Float.parseFloat(radiusStr);
            } catch (NumberFormatException ignored) {}
        }
        return 8.0f;
    }

    private static void applyCornerRadius(@Nullable View view, float radiusPx) {
        if (view == null) return;
        try {
            view.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View v, Outline outline) {
                    if (v != null && outline != null) {
                        outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radiusPx);
                    }
                }
            });
            view.setClipToOutline(true);
        } catch (Exception e) {
            Logger.printException(() -> "applyCornerRadius failure", e);
        }
    }

    private static int parseColor(String colorString, int defaultColor) {
        if (colorString == null || colorString.isEmpty()) {
            return defaultColor;
        }
        try {
            return Color.parseColor(colorString);
        } catch (Exception e) {
            return defaultColor;
        }
    }

    private static void applyCustomThemeToView(@Nullable View view) {
        if (view == null) return;
        try {
            GradientDrawable drawable = new GradientDrawable();

            boolean isDark = Utils.isDarkModeEnabled();
            String customColorString = isDark
                    ? Settings.CUSTOM_SNACKBAR_COLOR_DARK.get()
                    : Settings.CUSTOM_SNACKBAR_COLOR_LIGHT.get();

            int defaultBg = isDark ? 0xFF181825 : 0xFFE6E9EF;
            int backgroundColor = parseColor(customColorString, defaultBg);

            drawable.setColor(backgroundColor);

            float radiusDp = getCornerRadiusDp();
            DisplayMetrics metrics = view.getResources() != null
                    ? view.getResources().getDisplayMetrics()
                    : Resources.getSystem().getDisplayMetrics();
            float radiusPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, radiusDp, metrics);
            drawable.setCornerRadius(radiusPx);

            String strokeColorStr = Settings.CUSTOM_SNACKBAR_STROKE_COLOR.get();
            if (Utils.isNotEmpty(strokeColorStr)) {
                try {
                    int strokeColor = parseColor(strokeColorStr, Color.TRANSPARENT);
                    if (Color.alpha(strokeColor) != 0) {
                        int strokePx = (int) TypedValue.applyDimension(
                                TypedValue.COMPLEX_UNIT_DIP, 1.0f, metrics);
                        drawable.setStroke(strokePx, strokeColor);
                    }
                } catch (Exception ignored) {}
            }

            view.setBackground(drawable);
            applyCornerRadius(view, radiusPx);

            applyTextColorToTextViews(view, getCalculatedTextColor(backgroundColor), 0);
        } catch (Exception e) {
            Logger.printException(() -> "applyCustomThemeToView failure", e);
        }
    }

    private static void applyInvertedThemeToLegacyView(@Nullable View view) {
        if (view == null) return;
        try {
            boolean isDark = Utils.isDarkModeEnabled();
            // Stock YouTube displays inverted contrast: light snackbar in dark mode, dark in light mode.
            // With invert snackbar theme enabled, reverse that inversion so:
            // - Dark mode app -> dark snackbar (matching dark mode)
            // - Light mode app -> light snackbar (matching light mode)
            int backgroundColor = isDark ? YT_BLACK_TEXT : YT_WHITE_BACKGROUND;
            int textColor = isDark ? YT_WHITE_BACKGROUND : YT_BLACK_TEXT;

            GradientDrawable drawable = new GradientDrawable();
            drawable.setColor(backgroundColor);

            float radiusDp = getCornerRadiusDp();
            DisplayMetrics metrics = view.getResources() != null
                    ? view.getResources().getDisplayMetrics()
                    : Resources.getSystem().getDisplayMetrics();
            float radiusPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, radiusDp, metrics);
            drawable.setCornerRadius(radiusPx);

            view.setBackground(drawable);
            applyCornerRadius(view, radiusPx);

            applyTextColorToTextViews(view, textColor, 0);
        } catch (Exception e) {
            Logger.printException(() -> "applyInvertedThemeToLegacyView failure", e);
        }
    }

    private static void applyTextColorToTextViews(@Nullable View view, int textColor, int depth) {
        if (view == null || depth > 6) return;

        try {
            if (depth > 0 && ((view instanceof Button) || view.isClickable())) {
                // Do not touch action buttons or modify their inner views, text colors, or ripples.
                return;
            }

            if (depth > 0 && view.getBackground() != null) {
                view.setBackgroundColor(Color.TRANSPARENT);
            }

            if (view instanceof TextView) {
                ((TextView) view).setTextColor(textColor);
            } else if (view instanceof ViewGroup group) {
                for (int i = 0; i < group.getChildCount(); i++) {
                    applyTextColorToTextViews(group.getChildAt(i), textColor, depth + 1);
                }
            }
        } catch (Exception e) {
            Logger.printException(() -> "applyTextColorToTextViews failure", e);
        }
    }

    private static int getCalculatedTextColor(int currentBackgroundColor) {
        String customTextString = Settings.CUSTOM_SNACKBAR_TEXT_COLOR.get();
        if (Utils.isNotEmpty(customTextString)) {
            try {
                return parseColor(customTextString, Color.WHITE);
            } catch (Exception ignored) {}
        }

        double luminance = (0.299 * Color.red(currentBackgroundColor)
                + 0.587 * Color.green(currentBackgroundColor)
                + 0.114 * Color.blue(currentBackgroundColor)) / 255.0;
        return luminance > 0.5 ? Color.BLACK : Color.WHITE;
    }

    @Nullable
    public static Integer getTextColorForSpans() {
        try {
            if (isCustomSnackbarThemeEnabled()) {
                boolean isDark = Utils.isDarkModeEnabled();
                String customColorString = isDark
                        ? Settings.CUSTOM_SNACKBAR_COLOR_DARK.get()
                        : Settings.CUSTOM_SNACKBAR_COLOR_LIGHT.get();
                int defaultBg = isDark ? 0xFF181825 : 0xFFE6E9EF;
                int backgroundColor = parseColor(customColorString, defaultBg);
                return getCalculatedTextColor(backgroundColor);
            }

            if (isInvertSnackbarThemeEnabled()) {
                return Utils.isDarkModeEnabled() ? Color.WHITE : Color.BLACK;
            }
        } catch (Exception e) {
            Logger.printException(() -> "getTextColorForSpans failure", e);
        }

        return null;
    }

    public static Enum<?> invertSnackBarTheme(Enum<?> appTheme, Enum<?> darkTheme) {
        try {
            if (isInvertSnackbarThemeEnabled()) {
                if (appTheme == darkTheme) {
                    for (Object constant : Objects.requireNonNull(appTheme.getClass().getEnumConstants())) {
                        if (constant != darkTheme) {
                            return (Enum<?>) constant;
                        }
                    }
                    return null;
                } else {
                    return darkTheme;
                }
            }
        } catch (Exception e) {
            Logger.printException(() -> "invertSnackBarTheme failure", e);
        }
        return appTheme;
    }
}
