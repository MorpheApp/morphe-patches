/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.StringSetting;
import app.morphe.extension.shared.ui.Dim;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public final class SnackbarPatch {

    private static final boolean HIDE_SNACKBAR = Settings.HIDE_SNACKBAR.get();

    private static final boolean CUSTOM_THEME = !HIDE_SNACKBAR && Settings.CUSTOM_SNACKBAR_THEME.get();

    /**
     * Text color on a dark background. The snackbar of the light theme uses it.
     */
    private static final int LIGHT_TEXT_COLOR = Color.WHITE;

    /**
     * Text color on a light background. The snackbar of the dark theme uses it.
     */
    private static final int DARK_TEXT_COLOR = 0xFF0F0F0F;

    private static final int STROKE_WIDTH = Dim.dp1;

    private static final int DARK_BACKGROUND_COLOR;
    private static final int LIGHT_BACKGROUND_COLOR;
    private static final int STROKE_COLOR;
    private static final int CORNER_RADIUS;

    static {
        if (CUSTOM_THEME) {
            DARK_BACKGROUND_COLOR = loadColor(Settings.CUSTOM_SNACKBAR_COLOR_DARK);
            LIGHT_BACKGROUND_COLOR = loadColor(Settings.CUSTOM_SNACKBAR_COLOR_LIGHT);
            STROKE_COLOR = loadColor(Settings.CUSTOM_SNACKBAR_STROKE_COLOR);
            CORNER_RADIUS = Dim.dp(loadCornerRadius());
        } else {
            DARK_BACKGROUND_COLOR = 0;
            LIGHT_BACKGROUND_COLOR = 0;
            STROKE_COLOR = 0;
            CORNER_RADIUS = 0;
        }
    }

    /**
     * The Litho snackbar that was created last. Used only on the main thread.
     */
    private static WeakReference<View> lithoSnackbarRef = new WeakReference<>(null);

    private SnackbarPatch() {
    }

    @ColorInt
    private static int loadColor(StringSetting setting) {
        try {
            return Color.parseColor(setting.get());
        } catch (IllegalArgumentException ex) {
            Logger.printException(() -> "Invalid snackbar color: " + setting.get(), ex);
            return Color.parseColor(setting.resetToDefault());
        }
    }

    private static int loadCornerRadius() {
        final int radius = Settings.CUSTOM_SNACKBAR_CORNER_RADIUS.get();
        if (radius < 0) {
            Logger.printException(() -> "Invalid snackbar corner radius: " + radius);
            return Settings.CUSTOM_SNACKBAR_CORNER_RADIUS.resetToDefault();
        }
        return radius;
    }

    @ColorInt
    private static int getBackgroundColor() {
        return Utils.isDarkModeEnabled() ? DARK_BACKGROUND_COLOR : LIGHT_BACKGROUND_COLOR;
    }

    /**
     * @return The text color with the higher contrast ratio on the background, as defined by WCAG.
     */
    @ColorInt
    private static int getTextColor(@ColorInt int backgroundColor) {
        final float background = Color.luminance(backgroundColor) + 0.05f;
        final float dark = Color.luminance(DARK_TEXT_COLOR) + 0.05f;
        final float light = Color.luminance(LIGHT_TEXT_COLOR) + 0.05f;
        return background / dark > light / background ? DARK_TEXT_COLOR : LIGHT_TEXT_COLOR;
    }

    /**
     * @return The color of the snackbar text, or null if the text keeps its color.
     */
    @Nullable
    public static Integer getTextColor() {
        return CUSTOM_THEME ? getTextColor(getBackgroundColor()) : null;
    }

    /**
     * Injection point.
     */
    public static boolean hideSnackbar(boolean skipDisplay) {
        return skipDisplay || HIDE_SNACKBAR;
    }

    /**
     * Injection point.
     */
    public static void hideLithoSnackbar(FrameLayout frameLayout) {
        if (HIDE_SNACKBAR) {
            Utils.hideViewByLayoutParams(frameLayout);
        }
    }

    /**
     * Injection point.
     */
    public static void hideSnackbarView(View view) {
        if (HIDE_SNACKBAR) {
            Utils.hideViewByLayoutParams(view);
            view.setVisibility(View.GONE);
        }
    }

    /**
     * Injection point.
     * <p>
     * Called when a snackbar that is not drawn by Litho is created, before its content is added.
     */
    public static void onSnackbarCreated(View snackbar) {
        hideSnackbarView(snackbar);
        if (!CUSTOM_THEME) {
            return;
        }

        try {
            snackbar.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(@NonNull View view) {
                    try {
                        // The theme can change while the snackbar is kept.
                        final int backgroundColor = getBackgroundColor();
                        view.setBackground(createBackground(backgroundColor));
                        if (view instanceof ViewGroup group) {
                            setContentColors(group, getTextColor(backgroundColor));
                        }
                    } catch (Exception ex) {
                        Logger.printException(() -> "onViewAttachedToWindow failure", ex);
                    }
                }

                @Override
                public void onViewDetachedFromWindow(@NonNull View view) {
                }
            });
        } catch (Exception ex) {
            Logger.printException(() -> "onSnackbarCreated failure", ex);
        }
    }

    /**
     * Injection point.
     * <p>
     * Called when the view that holds a Litho snackbar is created.
     */
    public static void onLithoSnackbarCreated(FrameLayout snackbar) {
        if (!CUSTOM_THEME) {
            return;
        }

        try {
            // Litho mounts the component after the view is added to the window. The view is
            // forgotten once removed, so the color hook does no work while no snackbar is shown.
            lithoSnackbarRef = new WeakReference<>(snackbar);
            snackbar.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(@NonNull View view) {
                }

                @Override
                public void onViewDetachedFromWindow(@NonNull View view) {
                    if (lithoSnackbarRef.get() == view) {
                        lithoSnackbarRef = new WeakReference<>(null);
                    }
                }
            });

            // The Litho component draws over this background, which then shows only
            // where the corners of the component are rounder than the outline.
            snackbar.setBackgroundColor(getBackgroundColor());
            if (Color.alpha(STROKE_COLOR) != 0) {
                GradientDrawable stroke = new GradientDrawable();
                stroke.setCornerRadius(CORNER_RADIUS);
                stroke.setStroke(STROKE_WIDTH, STROKE_COLOR);
                snackbar.setForeground(stroke);
            }
            setCornerRadius(snackbar);
        } catch (Exception ex) {
            Logger.printException(() -> "onLithoSnackbarCreated failure", ex);
        }
    }

    /**
     * Injection point.
     * <p>
     * Called with the color of every Litho background.
     */
    public static int getLithoColor(Drawable drawable, int color) {
        if (!CUSTOM_THEME) {
            return color;
        }

        try {
            View snackbar = lithoSnackbarRef.get();
            if (snackbar == null) {
                return color;
            }

            // The background is drawn by the Litho root view,
            // which is inside the view that holds the Litho tree.
            View host = getDrawableView(drawable);
            if (host != null && host.getParent() instanceof View lithoView
                    && lithoView.getParent() == snackbar) {
                // The theme patch replaces some exact colors after this hook, such as the gray
                // backgrounds of the dark theme. The lowest bit of blue is not visible.
                return getBackgroundColor() ^ 1;
            }
        } catch (Exception ex) {
            Logger.printException(() -> "getLithoColor failure", ex);
        }
        return color;
    }

    @Nullable
    private static View getDrawableView(Drawable drawable) {
        Drawable.Callback callback = drawable.getCallback();
        while (callback instanceof Drawable parent) {
            callback = parent.getCallback();
        }
        return callback instanceof View view ? view : null;
    }

    private static GradientDrawable createBackground(@ColorInt int backgroundColor) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(backgroundColor);
        background.setCornerRadius(CORNER_RADIUS);
        if (Color.alpha(STROKE_COLOR) != 0) {
            background.setStroke(STROKE_WIDTH, STROKE_COLOR);
        }
        return background;
    }

    private static void setCornerRadius(View view) {
        view.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View outlineView, Outline outline) {
                outline.setRoundRect(0, 0, outlineView.getWidth(), outlineView.getHeight(), CORNER_RADIUS);
            }
        });
        view.setClipToOutline(true);
    }

    /**
     * Removes the backgrounds of the content, which can cover the background of the snackbar,
     * and colors the text. The action keeps its background, which shows when it is pressed.
     */
    private static void setContentColors(ViewGroup group, @ColorInt int textColor) {
        for (int i = 0, count = group.getChildCount(); i < count; i++) {
            View child = group.getChildAt(i);
            if (!child.isClickable()) {
                child.setBackground(null);
            }
            if (child instanceof TextView textView) {
                textView.setTextColor(textColor);
            } else if (child instanceof ViewGroup childGroup) {
                setContentColors(childGroup, textColor);
            }
        }
    }
}
