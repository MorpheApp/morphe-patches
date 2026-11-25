/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5245
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.patches;

import app.morphe.extension.reddit.settings.Settings;

@SuppressWarnings("unused")
public final class DisableRefreshOnBackPatch {

    /**
     * @return If this patch was included during patching.
     */
    @SuppressWarnings("SameReturnValue")
    public static boolean isPatchIncluded() {
        return false;  // Modified during patching.
    }

    /**
     * Injection point.
     * <p>
     * Called on back press on the home feed.
     *
     * @param original If back should scroll the feed to the top and reload it,
     *                 instead of exiting the app. True when the feed is scrolled down.
     */
    public static boolean refreshOnBack(boolean original) {
        return original && !Settings.DISABLE_REFRESH_ON_BACK.get();
    }

    /**
     * Injection point.
     * <p>
     * Called on back press on the home feed, when back exits the app.
     *
     * @param original If the "swipe again to exit" hint is shown.
     *                 Reddit shows it only the first few times.
     */
    public static boolean showExitHint(boolean original) {
        // Back now exits from any feed position, so always show the hint.
        return original || Settings.DISABLE_REFRESH_ON_BACK.get();
    }
}
