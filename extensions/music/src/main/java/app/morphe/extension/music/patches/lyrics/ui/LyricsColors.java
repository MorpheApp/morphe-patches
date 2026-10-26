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
    static final String APP_PRIMARY_TEXT_COLOR = "ytm_text_color_primary";

    /** The app's secondary text color, which is what the translation is drawn in. */
    static final String APP_SECONDARY_TEXT_COLOR = "ytm_text_color_secondary";

    // Dimmed until word is get sung so contrast look good
    static final float UNSUNG_ALPHA = 0.35f;

    // Opacity for active romanization reading text
    static final float ACTIVE_READING_ALPHA = 0.85f;

    // Active translation line opacity
    static final float ACTIVE_TRANSLATION_ALPHA = 0.6f;

    // Active background vocal opacity when it show up
    static final float ACTIVE_BG_ALPHA = 0.8f;

    static int secondaryTextColor() {
        ensureColorCache();
        return cachedSecondaryColor;
    }

    private static int computeSecondaryColor(int base) {
        int secondary = ResourceUtils.getColor(APP_SECONDARY_TEXT_COLOR, 0);
        if (secondary != 0) {
            return secondary;
        }
        return Color.argb(0x99, Color.red(base), Color.green(base), Color.blue(base));
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

    /** Falls back to the generic foreground color when the app ships no lyrics text color. */
    static int lineTextColor() {
        ensureColorCache();
        return cachedLineColor;
    }

    static int withAlpha(int color, float alpha) {
        final int a = Math.max(0, Math.min(255, Math.round(alpha * 255f)));
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color));
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