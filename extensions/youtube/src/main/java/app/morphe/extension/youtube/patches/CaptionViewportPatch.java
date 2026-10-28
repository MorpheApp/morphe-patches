/* Copyright 2026 Morphe. See NOTICE for the GPLv3 Section 7 terms. */
package app.morphe.extension.youtube.patches;

import android.content.res.Configuration;
import android.os.Looper;
import android.view.View;

import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.PlayerType;

import java.lang.ref.WeakReference;
import java.util.List;

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

    public static void onVideoId(String videoId) {
        policy.select(videoId);
        remeasure();
    }

    public static List<VideoFormat.FormatInterface> onFormats(
            String videoId, List<VideoFormat.FormatInterface> formats) {
        long bestArea = 0;
        int bestWidth = 0, bestHeight = 0;
        for (VideoFormat.FormatInterface format : formats) {
            try {
                if (format == null
                        || format.patch_getMimeType() == null
                        || !format.patch_getMimeType().startsWith("video/")) continue;
                int width = format.patch_getWidth(), height = format.patch_getHeight();
                if (width <= 0 || height <= 0 || width > 16384 || height > 16384) continue;
                long area = (long) width * height;
                if (area > bestArea) {
                    bestArea = area;
                    bestWidth = width;
                    bestHeight = height;
                }
            } catch (RuntimeException | LinkageError ignored) {
            }
        }
        if (bestArea > 0) {
            policy.record(videoId, bestWidth, bestHeight);
            remeasure();
        }
        return formats;
    }

    public static int normalizeHeight(View view, float scale, int width, int height) {
        if (view == null) return height;
        if (latestView.get() != view) latestView = new WeakReference<>(view);
        PlayerType type = PlayerType.getCurrent();
        boolean landscapeTransition =
                type == PlayerType.WATCH_WHILE_SLIDING_MAXIMIZED_FULLSCREEN
                        && view.getResources().getConfiguration().orientation
                                == Configuration.ORIENTATION_LANDSCAPE;
        boolean fullscreenFit =
                (type == PlayerType.WATCH_WHILE_FULLSCREEN || landscapeTransition)
                        && Settings.FULLSCREEN_VIDEO_SCALE.get()
                                == FullscreenVideoScalePatch.VideoScaleMode.DEFAULT;
        return policy.height(width, height, fullscreenFit, landscapeTransition);
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
            if (Looper.myLooper() == Looper.getMainLooper()) view.requestLayout();
            else view.post(view::requestLayout);
        }
    }
}
