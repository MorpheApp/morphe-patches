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

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.View;
import android.widget.Spinner;

import androidx.annotation.ColorInt;
import androidx.annotation.Nullable;

import com.airbnb.lottie.LottieAnimationView;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.patches.SplashAnimationPatch;
import app.morphe.extension.shared.patches.SplashAnimationPatch.SplashScreenAnimationStyle;
import app.morphe.extension.shared.settings.SharedYouTubeSettings;
import app.morphe.extension.shared.theme.BaseThemePatch;
import app.morphe.extension.shared.theme.ThemeColorPatch;
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
     * The glow animation is drawn on a dark square in the middle of the screen,
     * and the rest of the screen is given the same color.
     */
    @ColorInt
    private static final int RIVE_GLOW_ORIGINAL_BACKGROUND_COLOR = 0xFF09090A;

    /**
     * The dark square of the glow animation, as it is written in the Rive file:
     * a color keyframe (property key 88) followed by the ARGB color in little endian order.
     */
    private static final byte[] RIVE_GLOW_BACKGROUND_KEYFRAME = { 0x58, 0x0A, 0x09, 0x09, (byte) 0xFF };

    /**
     * The key the app keeps the Rive file of a raw resource in memory with, followed by the resource id.
     */
    private static final String RIVE_RAW_RESOURCE_KEY_PREFIX = "raw_res_";

    /**
     * The color the glow animation is drawn on, or 0 if the animation is not shown.
     */
    @ColorInt
    private static volatile int riveGlowBackgroundColor;

    private static boolean isRiveSplashScreenEnabled() {
        return SharedYouTubeSettings.SPLASH_SCREEN_STYLE.get() == SplashScreenAnimationStyle.RIVE_GLOW;
    }

    private static boolean isRiveGlowFile(int resourceId) {
        return resourceId == ResourceUtils.getIdentifier(ResourceType.RAW, "rive_phantom");
    }

    /**
     * Injection point.
     */
    public static boolean useRiveSplashScreen(boolean original) {
        return isRiveSplashScreenEnabled();
    }

    /**
     * The background color the system drew the splash screen of this launch with.
     *
     * @return The color, or 0 if it is not a color.
     */
    @ColorInt
    private static int getSplashScreenBackgroundColor(Activity activity) throws PackageManager.NameNotFoundException {
        PackageManager packageManager = activity.getPackageManager();

        int themeId = ThemeColorPatch.getLaunchSplashScreenTheme();
        if (themeId == 0) {
            themeId = packageManager.getActivityInfo(activity.getComponentName(), 0).getThemeResource();
        }

        // The system resolves the theme with the configuration of the device,
        // and not with the theme color variant that the contexts of the app use.
        Resources.Theme theme = packageManager
                .getResourcesForApplication(activity.getApplicationInfo())
                .newTheme();
        theme.applyStyle(themeId, true);

        // Android 12+ uses the splash screen background unless it is transparent,
        // and otherwise the window background.
        final int[] attributes = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ? new int[]{android.R.attr.windowBackground, android.R.attr.windowSplashScreenBackground}
                : new int[]{android.R.attr.windowBackground};

        TypedArray array = theme.obtainStyledAttributes(attributes);
        try {
            for (int i = attributes.length - 1; i >= 0; i--) {
                final int type = array.getType(i);
                if (type >= TypedValue.TYPE_FIRST_COLOR_INT && type <= TypedValue.TYPE_LAST_COLOR_INT) {
                    final int color = array.getColor(i, 0);
                    if (Color.alpha(color) != 0) {
                        return color;
                    }
                }
            }
            return 0;
        } finally {
            array.recycle();
        }
    }

    /**
     * Injection point.
     *
     * @param animationView The view that the Rive animation is drawn in.
     */
    public static void setRiveSplashScreenBackground(View animationView) {
        try {
            if (!isRiveSplashScreenEnabled() || !(animationView.getParent() instanceof View parent)) {
                return;
            }

            Context context = animationView.getContext();
            while (context instanceof ContextWrapper wrapper && !(context instanceof Activity)) {
                context = wrapper.getBaseContext();
            }

            // The glow animation is drawn on the color of the splash screen, so it continues
            // it. The Rive file is changed to use the color when it is read.
            int color = context instanceof Activity activity
                    ? getSplashScreenBackgroundColor(activity)
                    : 0;
            if (Color.alpha(color) != 0xFF) {
                Logger.printDebug(() -> "Splash screen background is not an opaque color");
                color = RIVE_GLOW_ORIGINAL_BACKGROUND_COLOR;
            }

            riveGlowBackgroundColor = color;
            parent.setBackgroundColor(color);
        } catch (Exception ex) {
            Logger.printException(() -> "setRiveSplashScreenBackground failure", ex);
        }
    }

    /**
     * Injection point.
     * <p>
     * The app keeps the Rive file of a raw resource in memory, and the key of the glow animation
     * is changed for each background color. Otherwise a file with the background color of
     * an earlier launch would be used.
     *
     * @param key The key of a raw resource, which is the prefix followed by the resource id.
     * @return The key to keep the Rive file in memory with.
     */
    public static String getRiveRawResourceKey(String key) {
        try {
            final int backgroundColor = riveGlowBackgroundColor;
            if (backgroundColor != 0
                    && backgroundColor != RIVE_GLOW_ORIGINAL_BACKGROUND_COLOR
                    && key.startsWith(RIVE_RAW_RESOURCE_KEY_PREFIX)
                    && isRiveGlowFile(Integer.parseInt(key.substring(RIVE_RAW_RESOURCE_KEY_PREFIX.length())))) {
                return key + "_" + Integer.toHexString(backgroundColor);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "getRiveRawResourceKey failure", ex);
        }

        return key;
    }

    /**
     * Injection point.
     * <p>
     * Called when the app reads the Rive file of a raw resource it has not cached yet.
     * The splash screen reads its file after the background is set.
     *
     * @param fileBytes  The Rive file of a raw resource, or null if it could not be read.
     * @param resourceId The raw resource the file was read from.
     * @return The Rive file to play.
     */
    @Nullable
    public static byte[] getRiveFile(@Nullable byte[] fileBytes, int resourceId) {
        try {
            final int backgroundColor = riveGlowBackgroundColor;
            if (fileBytes == null
                    || backgroundColor == 0
                    || backgroundColor == RIVE_GLOW_ORIGINAL_BACKGROUND_COLOR
                    || !isRiveGlowFile(resourceId)) {
                return fileBytes;
            }

            final int index = indexOf(fileBytes, RIVE_GLOW_BACKGROUND_KEYFRAME);
            if (index < 0) {
                Logger.printException(() -> "Could not find the background color of the glow animation");
                return fileBytes;
            }

            // Replace the color after the property key.
            ByteBuffer.wrap(fileBytes, index + 1, Integer.BYTES)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(backgroundColor);
        } catch (Exception ex) {
            Logger.printException(() -> "getRiveFile failure", ex);
        }

        return fileBytes;
    }

    private static int indexOf(byte[] array, byte[] target) {
        outer:
        for (int i = 0, last = array.length - target.length; i <= last; i++) {
            for (int j = 0; j < target.length; j++) {
                if (array[i + j] != target[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    /**
     * Injection point.
     * Modern Lottie style animation.
     */
    public static void setSplashAnimationLottie(LottieAnimationView view, int resourceId) {
        try {
            // The Morphe styles replace the YouTube logo animation with the Morphe one.
            if (SplashAnimationPatch.setBrandedSplashAnimation(view)) {
                return;
            }

            if (!SeekbarColorPatch.isCustomSeekbarColorEnabled()
                    // Black and white animations cannot use color replacements.
                    || SplashAnimationPatch.isMonochrome()) {
                view.patch_setAnimation(resourceId);
                return;
            }

            // The logo animates from the same red the progress bar is drawn with,
            // so only the fixed colors of the progress bar are replaced.
            SplashAnimationPatch.setSplashAnimationFixedColors(view, resourceId, Map.of(
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
        return SharedYouTubeSettings.SPLASH_SCREEN_STYLE.get() != SplashScreenAnimationStyle.DISABLED && original;
    }

    /**
     * Injection point.
     */
    public static int showSplashScreen(int i, int i2) {
        if (SharedYouTubeSettings.SPLASH_SCREEN_STYLE.get() != SplashScreenAnimationStyle.DISABLED || i != i2) {
            return i;
        }
        return i - 1;
    }

    /**
     * Injection point.
     */
    public static int getLoadingScreenType(int original) {
        SplashScreenAnimationStyle style = SharedYouTubeSettings.SPLASH_SCREEN_STYLE.get();

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
