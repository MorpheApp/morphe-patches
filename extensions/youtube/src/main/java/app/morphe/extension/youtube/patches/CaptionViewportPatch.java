/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4149
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import android.content.res.Configuration;
import android.view.View;

import java.lang.ref.WeakReference;
import java.util.List;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.PlayerType;

/** Preserve YouTube's font formula and preference scale, using known fitted content height. */
@SuppressWarnings("unused")
public final class CaptionViewportPatch {
    private static final CaptionViewportPolicy policy = new CaptionViewportPolicy();
    private static volatile WeakReference<View> latestView = new WeakReference<>(null);
    private static boolean lastFullscreen;
    private static FullscreenVideoScalePatch.VideoScaleMode lastScale;

    static {
        PlayerType.getOnChange().addObserver(type -> onPresentationChanged());
    }

    private CaptionViewportPatch() {}

    /**
     * Injection point.
     */
    public static void onVideoId(String videoId) {
        policy.select(videoId);
        remeasure();
    }

    /**
     * Injection point.
     */
    public static List<VideoFormat.FormatInterface> onFormats(
            String videoId, List<VideoFormat.FormatInterface> formats) {
        try {
            long bestArea = 0;
            int bestWidth = 0, bestHeight = 0;
            for (VideoFormat.FormatInterface format : formats) {
                String mimeType = format.patch_getMimeType();
                if (mimeType == null || !mimeType.startsWith("video/")) continue;
                int width = format.patch_getWidth(), height = format.patch_getHeight();
                long area = (long) width * height;
                if (area > bestArea) {
                    bestArea = area;
                    bestWidth = width;
                    bestHeight = height;
                }
            }
            if (bestArea > 0) {
                policy.record(videoId, bestWidth, bestHeight);
                remeasure();
            }
        } catch (Exception ex) {
            Logger.printException(() -> "onFormats failure", ex);
        }
        return formats;
    }

    /**
     * Injection point.
     *
     * @param scale  Caption size preference scale.
     * @param width  Width of the subtitle overlay.
     * @param height Height of the subtitle overlay.
     * @return Height to calculate the caption font size with.
     */
    public static int normalizeHeight(View view, float scale, int width, int height) {
        try {
            if (latestView.get() != view) latestView = new WeakReference<>(view);
            PlayerType type = PlayerType.getCurrent();
            final boolean landscapeTransition = type == PlayerType.WATCH_WHILE_SLIDING_MAXIMIZED_FULLSCREEN
                    && view.getResources().getConfiguration().orientation
                    == Configuration.ORIENTATION_LANDSCAPE;
            final boolean fullscreenFit = (type == PlayerType.WATCH_WHILE_FULLSCREEN || landscapeTransition)
                    && Settings.FULLSCREEN_VIDEO_SCALE.get() == FullscreenVideoScalePatch.VideoScaleMode.DEFAULT;
            return policy.height(width, height, fullscreenFit, landscapeTransition);
        } catch (Exception ex) {
            Logger.printException(() -> "normalizeHeight failure", ex);
            return height;
        }
    }

    /** A paused cue must be resized even when no new subtitle or format arrives. */
    public static synchronized void onPresentationChanged() {
        boolean fullscreen = PlayerType.getCurrent() == PlayerType.WATCH_WHILE_FULLSCREEN;
        FullscreenVideoScalePatch.VideoScaleMode scale = Settings.FULLSCREEN_VIDEO_SCALE.get();
        if (fullscreen == lastFullscreen && scale == lastScale) return;
        lastFullscreen = fullscreen;
        lastScale = scale;
        remeasure();
    }

    private static void remeasure() {
        View view = latestView.get();
        if (view != null) {
            Utils.runOnMainThreadNowOrLater(view::requestLayout);
        }
    }
}
