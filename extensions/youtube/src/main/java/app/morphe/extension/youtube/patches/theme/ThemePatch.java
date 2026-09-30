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

import com.airbnb.lottie.LottieAnimationView;

import java.util.Map;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.patches.SplashAnimationPatch;
import app.morphe.extension.shared.patches.SplashAnimationPatch.SplashScreenAnimationStyle;
import app.morphe.extension.shared.settings.SharedYouTubeSettings;
import app.morphe.extension.shared.theme.BaseThemePatch;
import app.morphe.extension.shared.theme.ThemeUtils;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public class ThemePatch extends BaseThemePatch {
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
    public static boolean useLotteLaunchSplashScreen(boolean original) {
        Logger.printDebug(() -> "Lottie splash screen flag: " + original);
        return true; // Force lottie animation view.
    }

    /**
     * Injection point.
     * Modern Lottie style animation.
     */
    public static void setSplashAnimationLottie(LottieAnimationView view, int resourceId) {
        try {
            // A custom branding icon replaces the YouTube logo animation with its own.
            if (SplashAnimationPatch.setBrandedSplashAnimation(view)) {
                return;
            }

            if (!SeekbarColorPatch.isCustomSeekbarColorEnabled()
                    // Black and white animations cannot use color replacements.
                    || SplashAnimationPatch.isMonochrome()) {
                view.patch_setAnimation(resourceId);
                return;
            }

            // Must specify primary key name otherwise the morphing YT logo color is also changed.
            SplashAnimationPatch.setSplashAnimation(view, resourceId, Map.of(
                    "[1,0,0.2,1]", SeekbarColorPatch.getSeekbarColor(),
                    "[1,0.152941176471,0.56862745098,1]", SeekbarColorPatch.getSeekbarAccentColor()
            ));
        } catch (Exception ex) {
            Logger.printException(() -> "setSplashAnimationLottie failure", ex);
        }
    }

    /**
     * Injection point.
     */
    public static boolean showSplashScreen(boolean original) {
        return SharedYouTubeSettings.SPLASH_SCREEN_ANIMATION_STYLE.get() != SplashScreenAnimationStyle.DISABLED && original;
    }

    /**
     * Injection point.
     */
    public static int showSplashScreen(int i, int i2) {
        if (SharedYouTubeSettings.SPLASH_SCREEN_ANIMATION_STYLE.get() != SplashScreenAnimationStyle.DISABLED || i != i2) {
            return i;
        }
        return i - 1;
    }

    /**
     * Injection point.
     */
    public static int getLoadingScreenType(int original) {
        SplashScreenAnimationStyle style = SharedYouTubeSettings.SPLASH_SCREEN_ANIMATION_STYLE.get();

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
