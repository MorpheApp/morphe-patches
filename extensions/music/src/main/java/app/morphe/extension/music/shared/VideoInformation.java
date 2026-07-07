/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to Morphe contributions.
 */

package app.morphe.extension.music.shared;

import androidx.annotation.NonNull;

import java.util.Objects;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * Hooking class for the current playing video.
 * Injection points and bridge methods are added by the VideoInformation patch.
 */
@SuppressWarnings("unused")
public final class VideoInformation {

    @NonNull
    private static String videoId = "";
    private static long videoLength = 0;
    private static long videoTime = -1;

    /** Injection point. */
    public static void initialize() {
        videoTime = -1;
        videoLength = 0;
        Logger.printDebug(() -> "VideoInformation: initialized");
    }

    /** Injection point. */
    public static void initializeMdx() {
        Logger.printDebug(() -> "VideoInformation: initialized MDX player");
    }

    /**
     * Id of the current video playing.
     *
     * @return The id of the video. Empty string if not set yet.
     */
    @NonNull
    public static String getVideoId() {
        return videoId;
    }

    /** Injection point. Stored for downstream patches; SponsorBlock routes its own id hook. */
    public static void setVideoId(@NonNull String newVideoId) {
        if (Objects.equals(newVideoId, videoId)) return;
        Logger.printDebug(() -> "VideoInformation: new video id: " + newVideoId);
        videoId = newVideoId;
    }

    /** Injection point. Called on main thread ~every 1000ms. */
    public static void setVideoTime(final long currentPlaybackTime) {
        videoTime = currentPlaybackTime;
    }

    /** Injection point. */
    public static void setVideoLength(final long length) {
        if (videoLength != length) videoLength = length;
    }

    public static long getVideoLength() { return videoLength; }

    /** Returns playback time in ms, or -1 if not yet initialized. */
    public static long getVideoTime() { return videoTime; }

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
            if (time <= 0 || len <= 0) {
                Logger.printDebug(() -> "seekTo: video not ready");
                return false;
            }
            // Seeking very close to the end causes a seek loop; push past the video end instead.
            final long adjusted = (len - seekTime > 500) ? seekTime : Integer.MAX_VALUE;
            Logger.printDebug(() -> "VideoInformation: seekTo " + adjusted + "ms");
            return overrideVideoTime(adjusted);
        } catch (Exception ex) {
            Logger.printException(() -> "seekTo failed", ex);
            return false;
        }
    }

    /**
     * Bridge method - implementation is injected by the patch via addStaticFieldToExtension.
     * Do not call directly; use {@link #seekTo(long)} instead.
     */
    @SuppressWarnings("SameReturnValue")
    public static boolean overrideVideoTime(final long seekTime) {
        Logger.printDebug(() -> "overrideVideoTime: " + seekTime + " (stub - patch not applied?)");
        return false;
    }
}
