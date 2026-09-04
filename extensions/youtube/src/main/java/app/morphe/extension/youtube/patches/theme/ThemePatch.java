/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2524
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches.theme;

import android.graphics.drawable.ColorDrawable;
import android.view.View;
import android.widget.Spinner;

import androidx.annotation.ColorInt;
import androidx.annotation.Nullable;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.theme.BaseThemePatch;
import app.morphe.extension.shared.theme.ThemeUtils;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public class ThemePatch extends BaseThemePatch {
    public enum SplashScreenAnimationStyle {
        // 0 int style exists in target app as a fall through default, but its value is repurposed to be disabled.
        DISABLED(0),
        FPS_60_ONE_SECOND(1),
        FPS_60_TWO_SECOND(2),
        FPS_60_FIVE_SECOND(3),
        FPS_60_BLACK_AND_WHITE(4),
        FPS_30_ONE_SECOND(5),
        FPS_30_TWO_SECOND(6),
        FPS_30_FIVE_SECOND(7),
        FPS_30_BLACK_AND_WHITE(8);
        // There exists a 10th JSON style used as the switch statement default,
        // but visually it is identical to 60fps one second.

        @Nullable
        static SplashScreenAnimationStyle styleFromOrdinal(int style) {
            // Alternatively can return using values()[style]
            for (SplashScreenAnimationStyle value : values()) {
                if (value.style == style) {
                    return value;
                }
            }

            return null;
        }

        final int style;

        SplashScreenAnimationStyle(int style) {
            this.style = style;
        }
    }

    // Color constants used in relation with litho components.
    private static final int[] WHITE_VALUES = {
            0xFFFFFFFF, // Comments chip background.
            0xFFF9F9F9, // Music related results panel background.
            0xFAFFFFFF, // Video chapters list background.
    };

    private static final int[] DARK_VALUES = {
            0xFF282828, // Explore drawer background.
            0xFF212121, // Comments chip background.
            0xFF181818, // Music related results panel background.
            0xFF0F0F0F, // Comments chip background (new layout).
            0xFA212121, // Video chapters list background.
            0xFF222225, // Flyout sub-menu background.
            0xFF0E0E10, // Playlist content background.
            0xFF09090A, // Watch history chip background.
    };

    /**
     * Injection point.
     * <p>
     * Change the color of Litho components.
     * If the color of the component matches one of the values, return the background color.
     *
     * @param originalValue The original color value.
     * @return The new or original color value.
     */
    @ColorInt
    public static int getValue(@ColorInt int originalValue) {
        return processColorValue(originalValue, DARK_VALUES, WHITE_VALUES);
    }

    /**
     * Injection point.
     */
    public static boolean gradientLoadingScreenEnabled(boolean original) {
        return Settings.GRADIENT_LOADING_SCREEN.get();
    }

    /**
     * Injection point.
     */
    public static boolean showSplashScreen(boolean original) {
        return Settings.SPLASH_SCREEN_ANIMATION_STYLE.get() != SplashScreenAnimationStyle.DISABLED && original;
    }

    /**
     * Injection point.
     */
    public static int showSplashScreen(int i, int i2) {
        if (Settings.SPLASH_SCREEN_ANIMATION_STYLE.get() != SplashScreenAnimationStyle.DISABLED || i != i2) {
            return i;
        }
        return i - 1;
    }

    /**
     * Injection point.
     */
    public static int getLoadingScreenType(int original) {
        SplashScreenAnimationStyle style = Settings.SPLASH_SCREEN_ANIMATION_STYLE.get();

        if (style == SplashScreenAnimationStyle.DISABLED) {
            return original;
        }

        final int replacement = style.style;
        if (original != replacement) {
            Logger.printDebug(() -> "Overriding splash screen style from: "
                    + SplashScreenAnimationStyle.styleFromOrdinal(original) + " to: " + style);
        }

        return replacement;
    }

    /**
     * Injection point.
     */
    public static void overrideSpinnerPopupBackground(View view) {
        try {
            if (view instanceof Spinner spinner) {
                int backgroundColor = ThemeUtils.getAppBackgroundColor();
                spinner.setPopupBackgroundDrawable(new ColorDrawable(backgroundColor));
            }
        } catch (Exception ex) {
            Logger.printException(() -> "overrideSpinnerPopupBackground failure", ex);
        }
    }
}
