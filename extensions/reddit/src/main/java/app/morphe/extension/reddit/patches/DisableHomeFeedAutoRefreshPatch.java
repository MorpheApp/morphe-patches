/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5244
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.patches;

import app.morphe.extension.reddit.settings.Settings;

@SuppressWarnings("unused")
public final class DisableHomeFeedAutoRefreshPatch {

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
     * Called when the home or following feed is shown again after the app was in the background
     * longer than a server set timeout. Reddit then reloads the feed and scrolls it to the top.
     *
     * @return True to skip the automatic reload.
     */
    public static boolean skipAutoRefresh() {
        return Settings.DISABLE_HOME_FEED_AUTO_REFRESH.get();
    }
}
