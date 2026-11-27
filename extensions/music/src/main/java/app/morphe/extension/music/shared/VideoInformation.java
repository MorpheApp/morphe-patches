/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.music.shared;

import android.os.SystemClock;

import androidx.annotation.NonNull;

import java.lang.ref.WeakReference;
import java.util.Collection;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.patches.BaseVideoInformation;

/**
 * Hooking class for the current playing video.
 * Injection points and bridge methods are added by the VideoInformation patch.
 */
@SuppressWarnings("unused")
public final class VideoInformation extends BaseVideoInformation {

    /**
     * Implemented by the player during patching.
     */
    public interface PlaybackController {
        boolean patch_seekTo(long videoTime);
    }

    /** Notified when the app starts another video. */
    public interface VideoIdListener {
        void newVideoId(@NonNull String videoId);
    }

    private static final Collection<VideoIdListener> videoIdListeners = new CopyOnWriteArrayList<>();

    @NonNull
    private static volatile String videoId = "";
    private static volatile long videoLength = 0;
    private static volatile long videoTime = -1;
    private static volatile long seekToZeroAtUptimeMs = -1;
    private static volatile WeakReference<PlaybackController> playerRef = new WeakReference<>(null);

    /** Injection point. Called each time the player is created. */
    public static void setPlayer(@NonNull PlaybackController player) {
        playerRef = new WeakReference<>(player);
    }

    /** Injection point. */
    public static void initialize() {
        videoTime = -1;
        videoLength = 0;
        seekToZeroAtUptimeMs = -1;
        Logger.printDebug(() -> "VideoInformation: initialized");
    }

    /** Injection point. */
    public static void initializeMdx() {
        Logger.printDebug(() -> "VideoInformation: initialized MDX player");
    }

    /**
     * ID of the current video playing.
     *
     * @return The id of the video. Empty string if not set yet.
     */
    @NonNull
    public static String getVideoId() {
        return videoId;
    }

    public static void addVideoIdListener(@NonNull VideoIdListener listener) {
        videoIdListeners.add(listener);
    }

    /** Injection point. Stored for downstream patches; SponsorBlock routes its own id hook. */
    public static void setVideoId(@NonNull String newVideoId) {
        if (Objects.equals(newVideoId, videoId)) return;
        Logger.printDebug(() -> "VideoInformation: new video id: " + newVideoId);
        videoId = newVideoId;

        for (VideoIdListener listener : videoIdListeners) {
            try {
                listener.newVideoId(newVideoId);
            } catch (Exception ex) {
                Logger.printException(() -> "newVideoId failure", ex);
            }
        }
    }

    /** Injection point. Called on main thread ~every 1000ms. */
    public static void setVideoTime(final long currentPlaybackTime) {
        videoTime = currentPlaybackTime;
    }

    /** Injection point. */
    public static void setVideoLength(final long length) {
        if (videoLength == length) return;
        Logger.printDebug(() -> "VideoInformation: new video length: " + length);
        videoLength = length;
    }

    public static long getVideoLength() { return videoLength; }

    /** Returns playback time in ms, or -1 if not yet initialized. */
    public static long getVideoTime() { return videoTime; }

    /**
     * True for a short window after an explicit {@link #seekTo(0)}. Position consumers use it
     * to tell a genuine rewind from a playback-state that reports zero before the real position.
     */
    public static boolean isRecentSeekToZero() {
        final long at = seekToZeroAtUptimeMs;
        return at >= 0 && SystemClock.uptimeMillis() - at < 2000L;
    }

    /**
     * Seeks the player to the given position in milliseconds.
     *
     * @return true if the seek was dispatched successfully
     */
    public static boolean seekTo(final long seekTime) {
        Utils.verifyOnMainThread();
        try {
            final long len = getVideoLength();
            final long time = getVideoTime();
            // Zero is a valid, ready position (especially right after seeking to the start).
            // Only the uninitialized -1 means the player has not reported a time yet.
            if (time < 0 || len <= 0) {
                Logger.printDebug(() -> "seekTo: video not ready");
                return false;
            }
            // Seeking very close to the end causes a seek loop; push past the video end instead.
            final long adjusted = (len - seekTime > 500) ? seekTime : Integer.MAX_VALUE;
            Logger.printDebug(() -> "VideoInformation: seekTo " + adjusted + "ms");
            final PlaybackController player = playerRef.get();
            if (player == null) {
                Logger.printDebug(() -> "seekTo: no player");
                return false;
            }
            final boolean seeked = player.patch_seekTo(adjusted);
            if (seeked) {
                // Include seekTime == 0: the ~1s progress hook is too slow for callers that
                // read the position right after rewinding (e.g. recording an A-B segment).
                videoTime = seekTime;
                if (seekTime == 0) {
                    seekToZeroAtUptimeMs = SystemClock.uptimeMillis();
                }
            }
            return seeked;
        } catch (Exception ex) {
            Logger.printException(() -> "seekTo failed", ex);
            return false;
        }
    }
}
