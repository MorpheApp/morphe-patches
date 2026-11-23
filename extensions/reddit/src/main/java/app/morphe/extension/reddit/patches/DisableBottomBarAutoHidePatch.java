/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.patches;

import app.morphe.extension.reddit.settings.Settings;

@SuppressWarnings("unused")
public final class DisableBottomBarAutoHidePatch {

    private static final float FULLY_VISIBLE = 1f;

    /**
     * @return If this patch was included during patching.
     */
    @SuppressWarnings("SameReturnValue")
    public static boolean isPatchIncluded() {
        return false;  // Modified during patching.
    }

    /**
     * Injection point.
     *
     * @param fraction 0 hidden, 1 fully shown.
     * @return How far the bottom bar is shown.
     */
    public static float getBarVisibleFraction(float fraction) {
        return Settings.DISABLE_BOTTOM_BAR_AUTO_HIDE.get() ? FULLY_VISIBLE : fraction;
    }
}
