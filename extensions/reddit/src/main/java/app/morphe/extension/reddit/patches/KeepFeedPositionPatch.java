/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3516
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.patches;

import app.morphe.extension.reddit.settings.Settings;

@SuppressWarnings("unused")
public final class KeepFeedPositionPatch {

    /**
     * @return If this patch was included during patching.
     */
    public static boolean isPatchIncluded() {
        return false;  // Modified during patching.
    }

    /**
     * Injection point.
     * <p>
     * Called when a post, image or video is opened from a feed. Reddit remembers the post and,
     * when the feed is shown again, re-creates the feed scroll state with that post at the top.
     *
     * @return True to not remember the opened post, so the feed keeps its scroll position.
     */
    public static boolean skipRememberOpenedPost() {
        return Settings.KEEP_FEED_POSITION.get();
    }
}
