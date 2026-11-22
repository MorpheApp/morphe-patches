/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5074
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.patches;

import app.morphe.extension.reddit.settings.Settings;

@SuppressWarnings("unused")
public final class HideVideosForYouShelfPatch {

    /**
     * @return If this patch was included during patching.
     */
    @SuppressWarnings("SameReturnValue")
    public static boolean isPatchIncluded() {
        return false;  // Modified during patching.
    }

    /**
     * Injection point.
     */
    public static boolean hideVideosForYouShelf() {
        return Settings.HIDE_VIDEOS_FOR_YOU_SHELF.get();
    }

}
