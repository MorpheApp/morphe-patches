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
     * Unselected buttons are a lighter grey in dark mode and a darker grey in light mode.
     */
    private static final float DARK_MODE_MIN_LUMINANCE = 0.179f;

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
     * @param color Compose color of an unselected icon or label.
     * @return The high-contrast color, or the original color if disabled.
     */
    public static long getUnselectedButtonColor(long color) {
        if (!ENABLED) {
            return color;
        }

        try {
            // Buttons on the glass bar must contrast with the glass, which can differ from the theme.
            final boolean dark = GlassNavigationBarPatch.isPatchIncluded() && Settings.GLASS_NAVIGATION_BAR.get()
                    ? GlassNavigationBarPatch.isGlassDark()
                    // A Compose color uses the same packed format as a color long.
                    : Color.luminance(color) > DARK_MODE_MIN_LUMINANCE;
            return dark ? WHITE : BLACK;
        } catch (Exception ex) {
            Logger.printException(() -> "getUnselectedButtonColor failure", ex);
        }

        return color;
    }
}
