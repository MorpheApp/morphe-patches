/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5192
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.patches;

import app.morphe.extension.reddit.settings.Settings;

@SuppressWarnings("unused")
public class HomeFeedTabsPatch {

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
     * @return If the tabs are shown. Always false if the modern home is disabled.
     */
    public static boolean showHomeFeedTabs() {
        return Settings.HOME_FEED_TABS.get() && Settings.HOME_FEED_TABS.isAvailable();
    }

    /**
     * Injection point.
     *
     * @param original If the tabs can be switched by swiping.
     */
    public static boolean enableTabSwipe(boolean original) {
        return showHomeFeedTabs() || original;
    }
}
