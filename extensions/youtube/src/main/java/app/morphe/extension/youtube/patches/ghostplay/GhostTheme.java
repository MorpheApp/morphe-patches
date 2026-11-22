/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5197
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches.ghostplay;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.provider.Settings;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;

import androidx.annotation.Nullable;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.theme.ThemeUtils;
import app.morphe.extension.shared.ui.Dim;

/**
 * Centralized visual tokens and micro-interaction helpers for Ghost Play.
 * Harmonized with Morphe's official brand identity (#1E5AA8 Blue -> #00AFAE Teal),
 * paired with a pure white ghost silhouette.
 */
public final class GhostTheme {

    // Official Morphe brand colors (from morphe_adaptive_foreground).
    private static final int MORPHE_BLUE = 0xFF1E5AA8;
    private static final int MORPHE_TEAL = 0xFF00AFAE;
    private static final int ACCENT_DARK = 0xFF19C6C4;
    private static final int ACCENT_LIGHT = 0xFF008F8E;
    private static final int ON_ACCENT = 0xFFFFFFFF;
    private static final int DANGER_COLOR = 0xFFEF5350;

    private GhostTheme() {
    }

    public static int morpheBlue() {
        return MORPHE_BLUE;
    }

    public static int morpheTeal() {
        return MORPHE_TEAL;
    }

    public static int accent() {
        return Utils.isDarkModeEnabled() ? ACCENT_DARK : ACCENT_LIGHT;
    }

    public static int onAccent() {
        return ON_ACCENT;
    }

    public static int accentSoft() {
        return adjustAlpha(MORPHE_TEAL, Utils.isDarkModeEnabled() ? 0.20f : 0.14f);
    }

    public static int accentBorder() {
        return adjustAlpha(accent(), Utils.isDarkModeEnabled() ? 0.52f : 0.42f);
    }

    public static int cardSurface() {
        return adjustAlpha(ThemeUtils.getAppForegroundColor(), 0.07f);
    }

    public static int subtleSurface() {
        return adjustAlpha(ThemeUtils.getAppForegroundColor(), 0.11f);
    }

    public static int subtleBorder() {
        return adjustAlpha(ThemeUtils.getAppForegroundColor(), 0.14f);
    }

    public static int mutedText() {
        return adjustAlpha(ThemeUtils.getAppForegroundColor(), 0.66f);
    }

    public static int danger() {
        return DANGER_COLOR;
    }

    public static int dangerSoft() {
        return adjustAlpha(DANGER_COLOR, 0.16f);
    }

    public static int adjustAlpha(int color, float factor) {
        int alpha = Math.round(Color.alpha(color) * factor);
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    public static GradientDrawable roundedRect(float radiusPx, int fillColor) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(radiusPx);
        d.setColor(fillColor);
        return d;
    }

    public static GradientDrawable outlinedRect(float radiusPx, int fillColor, int strokePx, int strokeColor) {
        GradientDrawable d = roundedRect(radiusPx, fillColor);
        d.setStroke(strokePx, strokeColor);
        return d;
    }

    /**
     * Morphe signature Blue -> Teal horizontal gradient rounded pill/button.
     */
    public static GradientDrawable morpheGradientRect(float radiusPx) {
        GradientDrawable d = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{MORPHE_BLUE, MORPHE_TEAL}
        );
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(radiusPx);
        return d;
    }

    /**
     * Morphe signature Blue -> Teal gradient circle (e.g., Power Orb when Ghost Mode is ON).
     */
    public static GradientDrawable morpheGradientCircle(int strokePx, int strokeColor) {
        GradientDrawable d = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{MORPHE_BLUE, MORPHE_TEAL}
        );
        d.setShape(GradientDrawable.OVAL);
        if (strokePx > 0) {
            d.setStroke(strokePx, strokeColor);
        }
        return d;
    }

    public static GradientDrawable circle(int fillColor, int strokePx, int strokeColor) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(fillColor);
        if (strokePx > 0) {
            d.setStroke(strokePx, strokeColor);
        }
        return d;
    }

    public static boolean areAnimationsEnabled(@Nullable Context context) {
        if (context == null) return true;
        try {
            float scale = Settings.Global.getFloat(
                    context.getContentResolver(),
                    Settings.Global.ANIMATOR_DURATION_SCALE,
                    1.0f
            );
            return scale > 0f;
        } catch (Exception ignored) {
            return true;
        }
    }

    /**
     * Plays a cute floating ghost bob + playful tilt wiggle when Ghost mode activates.
     */
    public static void playCuteGhostBounce(@Nullable View view, boolean active) {
        if (view == null || !areAnimationsEnabled(view.getContext())) return;
        view.animate().cancel();
        if (active) {
            ObjectAnimator floatY = ObjectAnimator.ofFloat(view, View.TRANSLATION_Y, 0f, -Dim.dp(5), Dim.dp(1), 0f);
            floatY.setDuration(420L);
            floatY.setInterpolator(new OvershootInterpolator(1.4f));

            ObjectAnimator wiggle = ObjectAnimator.ofFloat(view, View.ROTATION, 0f, -12f, 10f, -4f, 0f);
            wiggle.setDuration(420L);

            ObjectAnimator popX = ObjectAnimator.ofFloat(view, View.SCALE_X, 0.88f, 1.12f, 1.0f);
            ObjectAnimator popY = ObjectAnimator.ofFloat(view, View.SCALE_Y, 0.88f, 1.12f, 1.0f);
            popX.setDuration(320L);
            popY.setDuration(320L);

            AnimatorSet set = new AnimatorSet();
            set.playTogether(floatY, wiggle, popX, popY);
            set.start();
        } else {
            ObjectAnimator shake = ObjectAnimator.ofFloat(view, View.ROTATION, 0f, -7f, 5f, 0f);
            shake.setDuration(240L);
            shake.setInterpolator(new DecelerateInterpolator());

            ObjectAnimator dip = ObjectAnimator.ofFloat(view, View.TRANSLATION_Y, 0f, Dim.dp(2), 0f);
            dip.setDuration(240L);

            AnimatorSet set = new AnimatorSet();
            set.playTogether(shake, dip);
            set.start();
        }
    }

    /**
     * Plays a crisp spring press + sweep nudge on a timer icon (inspired by lucide-animated timer).
     */
    public static void playTimerNudge(@Nullable View view) {
        if (view == null || !areAnimationsEnabled(view.getContext())) return;
        ObjectAnimator press = ObjectAnimator.ofFloat(view, View.SCALE_Y, 1f, 0.86f, 1.05f, 1f);
        press.setDuration(320L);
        ObjectAnimator tilt = ObjectAnimator.ofFloat(view, View.ROTATION, 0f, 18f, -8f, 0f);
        tilt.setDuration(380L);
        AnimatorSet set = new AnimatorSet();
        set.playTogether(press, tilt);
        set.start();
    }
}
