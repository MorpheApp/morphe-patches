/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.shared.theme;

import android.graphics.Color;

import androidx.annotation.ColorInt;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;

/**
 * The background color of both themes of the app, which Morphe uses for its own dialogs and
 * settings so that they always match the app.
 * <p>
 * Each color is resolved from the app resources when it is first used, unless a patch handed one
 * in with {@link #setThemeLightColor(int)} or {@link #setThemeDarkColor(int)}. A patch that lets
 * the user pick the background knows the color of both themes, and only it can resolve the color
 * of the theme the app does not currently show.
 */
@SuppressWarnings("unused")
public class ThemeUtils {

    @ColorInt
    private static int lightColor = Color.WHITE;
    @ColorInt
    private static int darkColor = Color.BLACK;

    /**
     * If a color was handed in by a patch or read from the app resources, and must not be
     * resolved again.
     */
    private static boolean lightColorResolved;
    private static boolean darkColorResolved;

    /**
     * Injection point.
     */
    @SuppressWarnings("SameReturnValue")
    private static String getThemeLightColorResourceName() {
        // Value is changed by Settings patch.
        return "#FFFFFFFF";
    }

    /**
     * Injection point.
     */
    @SuppressWarnings("SameReturnValue")
    private static String getThemeDarkColorResourceName() {
        // Value is changed by Settings patch.
        return "#FF000000";
    }

    /**
     * Sets the theme light color used by the app.
     */
    public static void setThemeLightColor(@ColorInt int color) {
        Logger.printDebug(() -> "Setting theme light color: " + Utils.getColorHexString(color));
        lightColorResolved = true;
        lightColor = color;
    }

    /**
     * Sets the theme dark color used by the app.
     */
    public static void setThemeDarkColor(@ColorInt int color) {
        Logger.printDebug(() -> "Setting theme dark color: " + Utils.getColorHexString(color));
        darkColorResolved = true;
        darkColor = color;
    }

    /**
     * @return The background color of the light theme of the app.
     */
    @ColorInt
    public static int getThemeLightColor() {
        if (!lightColorResolved && Utils.isContextSet()) {
            setThemeLightColor(themeColor(getThemeLightColorResourceName(), Color.WHITE));
        }

        return lightColor;
    }

    /**
     * @return The background color of the dark theme of the app.
     */
    @ColorInt
    public static int getThemeDarkColor() {
        if (!darkColorResolved && Utils.isContextSet()) {
            setThemeDarkColor(themeColor(getThemeDarkColorResourceName(), Color.BLACK));
        }

        return darkColor;
    }

    @ColorInt
    private static int themeColor(String resourceName, @ColorInt int defaultColor) {
        try {
            return ResourceUtils.getColor(resourceName, defaultColor);
        } catch (Exception ex) {
            // This code can never be reached since a bad custom color will
            // fail during resource compilation. So no localized strings are needed here.
            Logger.printException(() -> "Invalid custom theme color: " + resourceName, ex);
            return defaultColor;
        }
    }

    @ColorInt
    public static int getDialogBackgroundColor() {
        if (Utils.isDarkModeEnabled()) {
            final int darkColor = getThemeDarkColor();
            return darkColor == Color.BLACK
                    // Lighten the background a little if using AMOLED dark theme
                    // as the dialogs are almost invisible.
                    ? 0xFF080808 // 3%
                    : darkColor;
        }

        return getThemeLightColor();
    }

    /**
     * @return The current app background color.
     */
    @ColorInt
    public static int getAppBackgroundColor() {
        return Utils.isDarkModeEnabled() ? getThemeDarkColor() : getThemeLightColor();
    }

    /**
     * @return The current app foreground color.
     */
    @ColorInt
    public static int getAppForegroundColor() {
        return Utils.isDarkModeEnabled() ? getThemeLightColor() : getThemeDarkColor();
    }

    @ColorInt
    public static int getOkButtonBackgroundColor() {
        return Utils.isDarkModeEnabled()
                // Must be inverted color.
                ? Color.WHITE
                : Color.BLACK;
    }

    @ColorInt
    public static int getCancelOrNeutralButtonBackgroundColor() {
        return Utils.isDarkModeEnabled()
                ? Utils.adjustColorBrightness(getDialogBackgroundColor(), 1.10f)
                : Utils.adjustColorBrightness(getThemeLightColor(), 0.95f);
    }

    @ColorInt
    public static int getEditTextBackground() {
        return Utils.isDarkModeEnabled()
                ? Utils.adjustColorBrightness(getDialogBackgroundColor(), 1.05f)
                : Utils.adjustColorBrightness(getThemeLightColor(), 0.97f);
    }

    private ThemeUtils() {
    }
}
