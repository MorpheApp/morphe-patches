/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.videoplayer.LoopVideoButton;

@SuppressWarnings("unused")
public class LoopVideoPatch {

    /** Range start in milliseconds. -1 means not set (full video loop). */
    public static volatile long rangeStartMs = -1;

    /** Range end in milliseconds. -1 means not set. */
    public static volatile long rangeEndMs = -1;

    /** True when rangeEndMs was set to the video length (user left end field empty). */
    public static volatile boolean rangeEndIsVideoEnd = false;

    /** Video ID at the time the range was set. Used to clear range when the video changes. */
    public static volatile String rangeVideoId = "";

    private static volatile long lastSeekTimeMs = 0;
    private static final long SEEK_COOLDOWN_MS = 2000;
    /** How early to seek before rangeEndMs when rangeEndIsVideoEnd, to avoid the end screen. */
    private static final long END_SCREEN_BUFFER_MS = 1500;

    public static boolean isRangeActive() {
        return rangeStartMs >= 0 && rangeEndMs > rangeStartMs;
    }

    public static void setRange(long startMs, long endMs, boolean endIsVideoEnd) {
        rangeStartMs = startMs;
        rangeEndMs = endMs;
        rangeEndIsVideoEnd = endIsVideoEnd;
        rangeVideoId = VideoInformation.getVideoId();
    }

    public static void clearRange() {
        rangeStartMs = -1;
        rangeEndMs = -1;
        rangeEndIsVideoEnd = false;
        rangeVideoId = "";
    }

    /**
     * Injection point. Called ~once per second with the current video time in milliseconds.
     */
    public static void videoTimeChanged(long time) {
        if (!Settings.LOOP_VIDEO.get() || !isRangeActive()) return;

        // Clear range if the video has changed since it was set.
        final String currentVideoId = VideoInformation.getVideoId();
        if (!rangeVideoId.isEmpty() && !rangeVideoId.equals(currentVideoId)) {
            clearRange();
            Settings.LOOP_VIDEO.save(false);
            LoopVideoButton.onRangeCleared();
            return;
        }

        final long triggerMs = rangeEndIsVideoEnd ? rangeEndMs - END_SCREEN_BUFFER_MS : rangeEndMs;
        if (time < triggerMs) return;

        final long now = System.currentTimeMillis();
        if (now - lastSeekTimeMs < SEEK_COOLDOWN_MS) return;
        lastSeekTimeMs = now;

        VideoInformation.seekTo(rangeStartMs);
    }

    /**
     * Injection point.
     */
    public static boolean shouldLoopVideo(Enum<?> status) {
        try {
            final boolean isEnded = Settings.LOOP_VIDEO.get()
                    && status != null && "ENDED".equals(status.name());
            if (isEnded) {
                return VideoInformation.seekTo(isRangeActive()
                        // Fallback: if the video truly ended while range is active (videoTimeChanged was too slow),
                        // seek to range start so the end screen is dismissed.
                        ? rangeStartMs
                        : 0);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "shouldLoopVideo failure", ex);
        }
        return false;
    }
}
