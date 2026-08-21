/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2524
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.music.patches.theme;

import androidx.annotation.ColorInt;

import app.morphe.extension.shared.theme.BaseThemePatch;

@SuppressWarnings("unused")
public class ThemePatch extends BaseThemePatch {

    // Color constants used in relation with litho components.
    private static final int[] DARK_VALUES = {
            0xFF212121, // Comments box background.
            0xFF030303, // Button container background in album.
            0xFF000000, // Button container background in playlist.
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
        return processColorValue(originalValue, DARK_VALUES, null);
    }
}
