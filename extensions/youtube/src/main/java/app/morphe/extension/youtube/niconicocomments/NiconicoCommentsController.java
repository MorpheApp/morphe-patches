/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4080
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 * 
 * Niconico-style live/replay comments implementation is adapted from
 * https://github.com/david419kr/niconico-yt-morphe-patches (GPLv3)
 * Feature design is based on david419kr/niconico-yt (MIT License)
 */

package app.morphe.extension.youtube.niconicocomments;

import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.preference.SeekBarPreference;
import app.morphe.extension.youtube.patches.VideoInformation;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.PlayerType;
import app.morphe.extension.youtube.shared.VideoState;

/**
 * Connects the player hooks, the chat source and the overlay view.
 */
@SuppressWarnings("unused")
public final class NiconicoCommentsController {
    private static final long SEEK_THRESHOLD_MS = 12_000;
    private static final Object SOURCE_LOCK = new Object();
    private static final Object VIDEO_CLOCK_LOCK = new Object();

    private static WeakReference<NiconicoCommentsOverlayView> overlayViewRef = new WeakReference<>(null);

    private static volatile String currentVideoId = "";
    private static volatile boolean playerVisible;
    private static volatile boolean videoPlaying;
    // Main thread only.
    private static boolean observersRegistered;

    // Guarded by VIDEO_CLOCK_LOCK.
    private static long sampledVideoTimeMs = -1;
    private static long sampleElapsedRealtimeMs;
    private static double playbackRate = 1.0;

    // Guarded by SOURCE_LOCK.
    @Nullable
    private static YouTubeChatSource activeSource;
    @Nullable
    private static Thread activeSourceThread;
    private static String completedSourceVideoId = "";

    private static final YouTubeChatSource.Listener CHAT_LISTENER = new YouTubeChatSource.Listener() {
        @Override
        public long getVideoTimeMs() {
            return getVideoTime();
        }

        @Override
        public void onComments(String videoId, List<NiconicoComment> comments, long liveSpreadWindowMs) {
            NiconicoCommentsOverlayView view = overlayViewRef.get();
            if (view != null && isAcceptingComments(videoId, liveSpreadWindowMs)) {
                List<NiconicoComment> shownComments = removeLongComments(comments,
                        SeekBarPreference.clampToRange(Settings.NICONICO_COMMENTS_MAX_LENGTH));
                view.post(() -> {
                    if (isAcceptingComments(videoId, liveSpreadWindowMs)) {
                        view.addComments(shownComments, liveSpreadWindowMs);
                    }
                });
            }
        }

        @Override
        public void onReplayReset(String videoId) {
            NiconicoCommentsOverlayView view = overlayViewRef.get();
            if (view != null && videoId.equals(currentVideoId)) {
                view.post(view::clearComments);
            }
        }
    };

    private NiconicoCommentsController() {
    }

    private static boolean isAcceptingComments(String videoId, long liveSpreadWindowMs) {
        return Settings.NICONICO_COMMENTS_SHOWN.get() && videoId.equals(currentVideoId)
                && shouldAcceptComments(liveSpreadWindowMs, videoPlaying, playerVisible);
    }

    static boolean shouldAcceptComments(long liveSpreadWindowMs, boolean playing, boolean visible) {
        // Replay uses zero and must keep its lookahead even while paused or minimized.
        return liveSpreadWindowMs <= 0 || (playing && visible);
    }

    static List<NiconicoComment> removeLongComments(List<NiconicoComment> comments, int maxLength) {
        if (maxLength >= Settings.NICONICO_COMMENTS_MAX_LENGTH_NO_LIMIT) {
            return comments;
        }
        List<NiconicoComment> shortComments = new ArrayList<>(comments.size());
        for (NiconicoComment comment : comments) {
            if (comment.length() <= maxLength) {
                shortComments.add(comment);
            }
        }
        return shortComments;
    }

    /**
     * Injection point.
     */
    public static void initialize(ViewGroup overlayRoot) {
        if (!Settings.NICONICO_COMMENTS.get()) return;

        try {
            if (!observersRegistered) {
                observersRegistered = true;
                PlayerType.getOnChange().addObserver(NiconicoCommentsController::setPlayerType);
                VideoState.getOnChange().addObserver(NiconicoCommentsController::setVideoState);
                VideoInformation.onPlaybackSpeedChange.addObserver(NiconicoCommentsController::setPlaybackRate);
                setPlaybackRate(VideoInformation.getPlaybackSpeed());
            }

            NiconicoCommentsOverlayView view = new NiconicoCommentsOverlayView(overlayRoot.getContext());
            view.setVideoId(currentVideoId);
            view.setClickable(false);
            view.setFocusable(false);
            view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            overlayRoot.addView(view, 0, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            overlayViewRef = new WeakReference<>(view);

            setPlayerType(PlayerType.getCurrent());
            setVideoState(VideoState.getCurrent());
        } catch (Exception ex) {
            Logger.printException(() -> "initialize failure", ex);
        }
    }

    /**
     * Injection point.
     */
    public static void newVideoLoaded(String videoId) {
        if (!Settings.NICONICO_COMMENTS.get()) return;

        try {
            if (videoId.equals(currentVideoId)) return;

            currentVideoId = videoId;
            synchronized (VIDEO_CLOCK_LOCK) {
                sampledVideoTimeMs = -1;
                sampleElapsedRealtimeMs = SystemClock.elapsedRealtime();
            }
            synchronized (SOURCE_LOCK) {
                completedSourceVideoId = "";
                stopChatSourceLocked();
            }

            NiconicoCommentsOverlayView view = overlayViewRef.get();
            if (view != null) {
                view.post(() -> {
                    view.setVideoId(videoId);
                    applyViewState(view);
                    maybeStartChatSource();
                });
            }
        } catch (Exception ex) {
            Logger.printException(() -> "newVideoLoaded failure", ex);
        }
    }

    /**
     * Injection point.
     */
    public static void setVideoTime(long videoTimeMs) {
        if (!Settings.NICONICO_COMMENTS.get()) return;

        try {
            final long nowMs = SystemClock.elapsedRealtime();
            final boolean seeked;
            synchronized (VIDEO_CLOCK_LOCK) {
                final long predictedVideoTimeMs = getVideoTimeLocked(nowMs);
                seeked = predictedVideoTimeMs >= 0
                        && Math.abs(videoTimeMs - predictedVideoTimeMs) > SEEK_THRESHOLD_MS;
                sampledVideoTimeMs = videoTimeMs;
                sampleElapsedRealtimeMs = nowMs;
            }

            NiconicoCommentsOverlayView view = overlayViewRef.get();
            if (view != null) {
                if (seeked) {
                    view.post(view::clearComments);
                } else {
                    view.postInvalidateOnAnimation();
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "setVideoTime failure", ex);
        }
    }

    private static void setPlayerType(PlayerType type) {
        playerVisible = type.isMaximizedOrFullscreen();
        updateViewState();
        maybeStartChatSource();
    }

    private static void setVideoState(@Nullable VideoState state) {
        final boolean nextPlaying = state == VideoState.PLAYING;
        final long nowMs = SystemClock.elapsedRealtime();
        synchronized (VIDEO_CLOCK_LOCK) {
            if (videoPlaying && !nextPlaying) {
                sampledVideoTimeMs = getVideoTimeLocked(nowMs);
            }
            sampleElapsedRealtimeMs = nowMs;
            videoPlaying = nextPlaying;
        }
        updateViewState();
    }

    private static void setPlaybackRate(float rate) {
        final long nowMs = SystemClock.elapsedRealtime();
        synchronized (VIDEO_CLOCK_LOCK) {
            // Keep the position reached at the old rate.
            if (sampledVideoTimeMs >= 0 && videoPlaying) {
                sampledVideoTimeMs = getVideoTimeLocked(nowMs);
            }
            sampleElapsedRealtimeMs = nowMs;
            playbackRate = sanitizePlaybackRate(rate);
        }

        NiconicoCommentsOverlayView view = overlayViewRef.get();
        if (view != null) {
            view.postInvalidateOnAnimation();
        }
    }

    /**
     * @return The interpolated video time, or -1 if no time is known for the current video.
     */
    static long getVideoTime() {
        final long nowMs = SystemClock.elapsedRealtime();
        synchronized (VIDEO_CLOCK_LOCK) {
            return getVideoTimeLocked(nowMs);
        }
    }

    private static long getVideoTimeLocked(long nowMs) {
        if (sampledVideoTimeMs < 0 || !videoPlaying) {
            return sampledVideoTimeMs;
        }
        return interpolateVideoTime(sampledVideoTimeMs,
                Math.max(0, nowMs - sampleElapsedRealtimeMs), playbackRate);
    }

    static long interpolateVideoTime(long sampledTimeMs, long elapsedRealtimeMs, double rate) {
        if (elapsedRealtimeMs <= 0) {
            return sampledTimeMs;
        }

        final double advancedVideoTimeMs = elapsedRealtimeMs * sanitizePlaybackRate(rate);
        if (advancedVideoTimeMs >= Long.MAX_VALUE - sampledTimeMs) {
            return Long.MAX_VALUE;
        }
        return sampledTimeMs + Math.round(advancedVideoTimeMs);
    }

    static double sanitizePlaybackRate(double rate) {
        return rate > 0.0 && !Double.isNaN(rate) && !Double.isInfinite(rate) ? rate : 1.0;
    }

    /**
     * Shows or hides the comments.
     */
    static void toggleCommentsShown() {
        final boolean shown = !Settings.NICONICO_COMMENTS_SHOWN.get();
        Settings.NICONICO_COMMENTS_SHOWN.save(shown);

        synchronized (SOURCE_LOCK) {
            if (shown) {
                // Retry a video whose source ended, such as after a network error.
                completedSourceVideoId = "";
            } else {
                stopChatSourceLocked();
            }
        }

        NiconicoCommentsOverlayView view = overlayViewRef.get();
        if (view != null && !shown) {
            view.clearComments();
        }
        updateViewState();
        maybeStartChatSource();
    }

    /**
     * Applies the current settings and player state to the overlay.
     * Can be called from any thread.
     */
    static void updateViewState() {
        NiconicoCommentsOverlayView view = overlayViewRef.get();
        if (view != null) {
            Utils.runOnMainThread(() -> applyViewState(view));
        }
    }

    private static void applyViewState(NiconicoCommentsOverlayView view) {
        view.setCommentFontSizeSp(SeekBarPreference.clampToRange(Settings.NICONICO_COMMENTS_FONT_SIZE));
        view.setCommentOpacityPercent(SeekBarPreference.clampToRange(Settings.NICONICO_COMMENTS_OPACITY));
        view.setTravelDurationSeconds(SeekBarPreference.clampToRange(Settings.NICONICO_COMMENTS_TRAVEL_DURATION));
        view.setOverlayActive(Settings.NICONICO_COMMENTS_SHOWN.get()
                && playerVisible && !currentVideoId.isEmpty());
        view.setVideoPlaying(videoPlaying);
    }

    /**
     * Starts loading the chat of the current video, if it is shown in the full size player.
     */
    private static void maybeStartChatSource() {
        final String videoId = currentVideoId;
        if (!Settings.NICONICO_COMMENTS_SHOWN.get() || !playerVisible
                || videoId.isEmpty() || overlayViewRef.get() == null) {
            return;
        }

        synchronized (SOURCE_LOCK) {
            if (videoId.equals(completedSourceVideoId) || activeSource != null) {
                return;
            }

            YouTubeChatSource source = new YouTubeChatSource(videoId, CHAT_LISTENER);
            Thread thread = new Thread(() -> {
                try {
                    source.run();
                } finally {
                    synchronized (SOURCE_LOCK) {
                        if (activeSource == source) {
                            activeSource = null;
                            activeSourceThread = null;
                            completedSourceVideoId = videoId;
                        }
                    }
                }
            }, "NiconicoYouTubeChat");
            thread.setDaemon(true);
            activeSource = source;
            activeSourceThread = thread;
            thread.start();
        }
    }

    private static void stopChatSourceLocked() {
        if (activeSource != null) {
            activeSource.stop();
            activeSource = null;
        }
        if (activeSourceThread != null) {
            activeSourceThread.interrupt();
            activeSourceThread = null;
        }
    }
}
