/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5460
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.patches;

import android.graphics.Color;

import app.morphe.extension.reddit.settings.Settings;
import app.morphe.extension.shared.Logger;

@SuppressWarnings("unused")
public final class HighContrastNavigationButtonsPatch {

    private static final boolean ENABLED = Settings.HIGH_CONTRAST_NAVIGATION_BUTTONS.get();

    private static final long BLACK = 0xFF000000L << 32;
    private static final long WHITE = 0xFFFFFFFFL << 32;

    /**
     * @return If this patch was included during patching.
     */
    @SuppressWarnings("SameReturnValue")
    public static boolean isPatchIncluded() {
        return false; // Modified during patching.
    }

    /**
     * Injection point.
     *
     * @param color Packed Compose sRGB color of an unselected button, with the ARGB color in the upper 32 bits.
     * @return The high-contrast color, or the original color if disabled.
     */
    public static long getUnselectedButtonColor(long color) {
        if (!ENABLED) {
            return color;
        }

        try {
            final boolean dark = GlassNavigationBarPatch.isPatchIncluded()
                    && Settings.GLASS_NAVIGATION_BAR.get()
                    ? GlassNavigationBarPatch.isGlassDark()
                    : Color.luminance((int) (color >>> 32)) > 0.179f;
            return dark ? WHITE : BLACK;
        } catch (Exception ex) {
            Logger.printException(() -> "getUnselectedButtonColor failure", ex);
        }

        return color;
    }
}
