/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3114
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.series;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;

import app.morphe.extension.shared.Logger;

/** Both observations come from the same current non-casting player controller. */
public final class PlaybackBridge {
    public interface Source {
        String patch_seriesTrackerVideoId();

        long patch_seriesTrackerPosition();
    }

    private static volatile WeakReference<Source> current = new WeakReference<>(null);

    public static void attach(@Nullable Source source) {
        current = new WeakReference<>(source);
    }

    @Nullable
    public static Source source() {
        return current.get();
    }

    public static String videoId() {
        return videoId(current.get());
    }

    /**
     * @return The video id, or an empty string if the controller is still
     *         being constructed or was already released.
     */
    static String videoId(@Nullable Source source) {
        if (source == null) return "";
        try {
            String id = source.patch_seriesTrackerVideoId();
            return id == null ? "" : id;
        } catch (RuntimeException ex) {
            Logger.printDebug(() -> "Video id is not available", ex);
            return "";
        }
    }

    /**
     * @return The playback position, or -1 if the controller is not available.
     */
    static long position(@Nullable Source source) {
        if (source == null) return -1;
        try {
            return source.patch_seriesTrackerPosition();
        } catch (RuntimeException ex) {
            Logger.printDebug(() -> "Video position is not available", ex);
            return -1;
        }
    }

    private PlaybackBridge() {}
}
