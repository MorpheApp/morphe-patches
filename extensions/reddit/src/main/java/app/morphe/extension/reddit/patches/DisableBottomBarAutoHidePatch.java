/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5242
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.patches;

import app.morphe.extension.reddit.settings.Settings;

@SuppressWarnings("unused")
public final class DisableBottomBarAutoHidePatch {

    private static final float NAV_BAR_FULLY_SHOWN = 1f;

    /**
     * @return If this patch was included during patching.
     */
    @SuppressWarnings("SameReturnValue")
    public static boolean isPatchIncluded() {
        return false;  // Modified during patching.
    }

    /**
     * Injection point. Also called by Feed auto-scroll, which can be included without this patch.
     *
     * @param fraction How much of the navigation bar is shown, from 0 to 1.
     * @return The fraction of the navigation bar to show.
     */
    public static float getNavBarShownFraction(float fraction) {
        return isPatchIncluded() && Settings.DISABLE_BOTTOM_BAR_AUTO_HIDE.get()
                ? NAV_BAR_FULLY_SHOWN
                : fraction;
    }
}
