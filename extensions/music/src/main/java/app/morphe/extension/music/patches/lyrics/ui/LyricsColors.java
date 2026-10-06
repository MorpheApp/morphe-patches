/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2269
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

import android.graphics.Color;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.theme.ThemeUtils;

final class LyricsColors {
    /** Color the app uses for primary text. */
    static final String APP_PRIMARY_TEXT_COLOR = "ytm_text_color_primary";

    /** Color the app uses for secondary text, applied to the translation. */
    static final String APP_SECONDARY_TEXT_COLOR = "ytm_text_color_secondary";

    /** Alpha channel for the unsung (not-yet-sung) word color. */
    static final float UNSUNG_ALPHA = 0.4f;

    static int secondaryTextColor() {
        ensureColorCache();
        return cachedSecondaryColor;
    }

    private static int computeSecondaryColor(int base) {
        int secondary = ResourceUtils.getColor(APP_SECONDARY_TEXT_COLOR, 0);
        if (secondary != 0) {
            return secondary;
        }
        return Color.argb(0x66, Color.red(base), Color.green(base), Color.blue(base));
    }

    private static int cachedLineColor;
    private static int cachedUnsungColor;
    private static int cachedSecondaryColor;
    private static boolean colorCacheValid;

    private static void ensureColorCache() {
        if (!colorCacheValid) {
            final int base = computeLineColor();
            cachedLineColor = base;
            cachedUnsungColor = Color.argb(Math.round(UNSUNG_ALPHA * 255f),
                    Color.red(base), Color.green(base), Color.blue(base));
            cachedSecondaryColor = computeSecondaryColor(base);
            colorCacheValid = true;
        }
    }

    static int unsungWordColor() {
        ensureColorCache();
        return cachedUnsungColor;
    }

    /**
     * Color the app uses for lyrics text, falling back to the generic foreground color.
     */
    static int lineTextColor() {
        ensureColorCache();
        return cachedLineColor;
    }

    private static int computeLineColor() {
        final int colorId = ResourceUtils.getIdentifier(ResourceType.COLOR, APP_PRIMARY_TEXT_COLOR);
        if (colorId == 0) {
            return ThemeUtils.getAppForegroundColor();
        }
        return ResourceUtils.getColor(APP_PRIMARY_TEXT_COLOR, ThemeUtils.getAppForegroundColor());
    }

    static void invalidate() {
        colorCacheValid = false;
    }
}
