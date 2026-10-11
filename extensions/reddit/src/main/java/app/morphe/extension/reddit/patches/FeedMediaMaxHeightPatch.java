/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3516
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.patches;

import app.morphe.extension.reddit.settings.Settings;

@SuppressWarnings("unused")
public final class FeedMediaMaxHeightPatch {

    private static final float MIN_RATIO = 0.25f;
    private static final float MAX_RATIO = 5f;

    /**
     * @return If this patch was included during patching.
     */
    public static boolean isPatchIncluded() {
        return false;  // Modified during patching.
    }

    /**
     * Injection point.
     *
     * @return Maximum height to width ratio of media.
     */
    public static float getMaxHeightRatio() {
        return Math.max(MIN_RATIO, Math.min(Settings.FEED_MEDIA_MAX_HEIGHT.get(), MAX_RATIO));
    }

    /**
     * Injection point.
     *
     * @return Maximum height of media, in pixels.
     */
    public static int getMaxHeight(int width) {
        return (int) (width * getMaxHeightRatio());
    }
}
