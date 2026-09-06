/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2524
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.shared.theme;

import androidx.annotation.ColorInt;
import androidx.annotation.Nullable;

@SuppressWarnings("unused")
public abstract class BaseThemePatch {
    /**
     * Check if a value matches any of the provided values.
     *
     * @param value The value to check.
     * @param of    The array of values to compare against.
     * @return True if the value matches any of the provided values.
     */
    protected static boolean anyEquals(int value, int... of) {
        for (int v : of) {
            if (value == v) {
                return true;
            }
        }
        return false;
    }

    /**
     * Helper method to process color values for Litho components.
     *
     * @param originalValue The original color value.
     * @param darkValues    Array of dark mode color values to match.
     * @param lightValues   Array of light mode color values to match.
     * @return The new or original color value.
     */
    @ColorInt
    protected static int processColorValue(int originalValue, int[] darkValues, @Nullable int[] lightValues) {
        // A Litho component is also given the color the app uses for its own text and icons,
        // such as the background of the chip that is selected, which is drawn with it.
        final int foreground = ThemeColorPatch.getForegroundColor(originalValue);
        if (foreground != originalValue) {
            return foreground;
        }

        // The values that are replaced here are the backgrounds the app hard codes into its
        // Litho components. They only have to follow along when the app background is changed.
        // With the background of the app itself the app is left alone. Otherwise, a component
        // such as the chip bar of the feed is given the color of another background of the app.
        // It then shows as a lighter gray than it does unpatched.
        if (ThemeColorPatch.isAppDefaultColor()) {
            return originalValue;
        }

        // The color of a background must not be read from the current context of the app, which
        // carries the resource variant of one theme only.
        final boolean dark = ThemeColorPatch.isDarkTheme();
        int[] values = dark ? darkValues : lightValues;

        if (values != null && anyEquals(originalValue, values)) {
            return ThemeColorPatch.themeColor(dark);
        }

        return originalValue;
    }
}
