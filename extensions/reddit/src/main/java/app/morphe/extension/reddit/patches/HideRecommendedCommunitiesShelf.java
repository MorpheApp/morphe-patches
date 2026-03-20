/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */
package app.morphe.extension.reddit.patches;

import app.morphe.extension.reddit.settings.Settings;

@SuppressWarnings("unused")
public final class HideRecommendedCommunitiesShelf {

    /**
     * @return If this patch was included during patching.
     */
    public static boolean isPatchIncluded() {
        return false;  // Modified during patching.
    }

    public static boolean hideRecommendedCommunitiesShelf() {
        return Settings.HIDE_RECOMMENDED_COMMUNITIES_SHELF.get();
    }

}
