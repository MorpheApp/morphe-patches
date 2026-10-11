/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3516
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.patches;

import android.content.res.Resources;

import app.morphe.extension.reddit.settings.Settings;

@SuppressWarnings("unused")
public final class FullWidthFeedMediaPatch {

    /**
     * @return If this patch was included during patching.
     */
    public static boolean isPatchIncluded() {
        return false;  // Modified during patching.
    }

    /**
     * Injection point.
     * <p>
     * Feed post styles inset media with side padding and rounded corners when this is true.
     */
    public static boolean isMediaInsetEnabled(boolean original) {
        return original && !Settings.FULL_WIDTH_FEED_MEDIA.get();
    }

    /**
     * Injection point.
     * <p>
     * Media height is computed for the screen width minus this inset (in pixels).
     */
    public static int getMediaInset(int original) {
        return Settings.FULL_WIDTH_FEED_MEDIA.get() ? 0 : original;
    }

    /**
     * @return The content of post details content props.
     */
    public static Object getPostContent(Object props) {
        return null;  // Modified during patching.
    }

    /**
     * @return If post content is a GIF or video.
     */
    public static boolean isVideoContent(Object content) {
        return false;  // Modified during patching.
    }

    /**
     * Injection point.
     * <p>
     * The post details pad videos at the sides, with the rest of the post content.
     */
    public static boolean isPostContentInset(boolean original, Object props) {
        if (!original || !Settings.FULL_WIDTH_FEED_MEDIA.get()) return original;
        return !isVideoContent(getPostContent(props));
    }

    /**
     * Injection point.
     * <p>
     * Display width of a video in the post details, in pixels.
     */
    public static int getPostVideoWidth(int width) {
        if (!Settings.FULL_WIDTH_FEED_MEDIA.get() || width <= 0) return width;
        return Resources.getSystem().getDisplayMetrics().widthPixels;
    }

    /**
     * Injection point.
     * <p>
     * Display height of a video in the post details, in pixels, scaled to the full width.
     */
    public static int getPostVideoHeight(int width, int height) {
        if (!Settings.FULL_WIDTH_FEED_MEDIA.get() || width <= 0 || height <= 0) return height;
        return (int) ((long) height * getPostVideoWidth(width) / width);
    }
}
