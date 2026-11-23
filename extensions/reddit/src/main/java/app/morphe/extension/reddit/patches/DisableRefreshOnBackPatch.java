/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
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
        return false; // Modified during patching.
    }

    /**
     * Injection point.
     * <p>
     * Called on back press to determine if the home feed is scrolled down.
     *
     * @param scrolled Whether the feed is scrolled down.
     * @return False to skip scrolling to the top and reloading.
     */
    public static boolean isFeedScrolled(boolean scrolled) {
        return scrolled && !Settings.DISABLE_REFRESH_ON_BACK.get();
    }

    /**
     * Injection point.
     * <p>
     * Called on back press to determine if the "Tap again to exit" hint should be shown.
     *
     * @param show Whether to show the exit hint.
     * @return True to always show the exit hint.
     */
    public static boolean showExitHint(boolean show) {
        return show || Settings.DISABLE_REFRESH_ON_BACK.get();
    }
}
