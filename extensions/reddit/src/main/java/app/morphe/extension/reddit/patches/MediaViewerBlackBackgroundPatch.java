/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3516
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.patches;

import android.app.Activity;
import android.content.ContextWrapper;

import app.morphe.extension.reddit.settings.Settings;

@SuppressWarnings("unused")
public final class MediaViewerBlackBackgroundPatch {

    /**
     * Compose Color.Black: ARGB in the upper 32 bits, sRGB color space.
     */
    private static final long BLACK = 0xFF000000L << 32;

    private static final String MEDIA_VIEWER_ACTIVITY = "com.reddit.fullbleedplayer.common.FbpActivity";

    /**
     * @return If this patch was included during patching.
     */
    public static boolean isPatchIncluded() {
        return false;  // Modified during patching.
    }

    /**
     * Injection point.
     *
     * @return Background color of the media viewer, as a Compose color.
     */
    public static long getBackgroundColor(long original) {
        return Settings.MEDIA_VIEWER_BLACK_BACKGROUND.get() ? BLACK : original;
    }

    /**
     * Injection point.
     * The video player is shared with the feed, so only change it inside the media viewer.
     *
     * @param context Context of the video player composition.
     * @return Background color of the video player, as a Compose color.
     */
    public static long getVideoPlayerBackgroundColor(long original, Object context) {
        if (!Settings.MEDIA_VIEWER_BLACK_BACKGROUND.get()) {
            return original;
        }

        Object current = context;
        while (current instanceof ContextWrapper && !(current instanceof Activity)) {
            current = ((ContextWrapper) current).getBaseContext();
        }
        return current != null && current.getClass().getName().equals(MEDIA_VIEWER_ACTIVITY)
                ? BLACK
                : original;
    }
}
