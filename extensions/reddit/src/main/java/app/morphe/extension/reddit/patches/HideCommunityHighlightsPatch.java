/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5297
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.patches;

import app.morphe.extension.reddit.settings.Settings;

@SuppressWarnings("unused")
public final class HideCommunityHighlightsPatch {

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
     * @param hidden If Reddit hides the section, because it has no highlights.
     */
    public static Boolean hideCommunityHighlights(Boolean hidden) {
        return Settings.HIDE_COMMUNITY_HIGHLIGHTS.get() ? Boolean.TRUE : hidden;
    }
}
