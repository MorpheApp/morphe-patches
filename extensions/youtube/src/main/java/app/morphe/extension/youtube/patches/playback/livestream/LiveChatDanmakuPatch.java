/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.playback.livestream;

import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.youtube.patches.VideoInformation;
import app.morphe.extension.youtube.settings.Settings;

/**
 * Injection points for the live chat danmaku patch.
 *
 * Wiring (see the Kotlin patch of the same name):
 * <ul>
 *   <li>{@code newVideoStarted} / {@code videoTimeChanged} come from the
 *       existing video-information hooks, so no new fingerprint is needed
 *       for playback state.</li>
 *   <li>{@code setPlayerOverlay} comes from the player overlay container
 *       fingerprint. Several overlay containers exist per player; the
 *       largest one after layout is used, so danmaku covers the video
 *       area rather than a small controls container.</li>
 *   <li>{@code onChatMessage} is the future, preferred feed: if a
 *       fingerprint for the in-app live chat renderer is added later,
 *       it can call this directly and the polling source below becomes
 *       unnecessary. Until then, public chat is polled (see
 *       {@link LiveChatPollingSource}).</li>
 * </ul>
 */
@SuppressWarnings("unused")
public final class LiveChatDanmakuPatch {

    private static final String LOCK_MODE_OVERLAY_NAME = "player_overlay_lock_mode";
    private static final long OVERLAY_PICK_DELAY_MS = 500;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static DanmakuOverlay overlay;
    private static LiveChatPollingSource pollingSource;

    private static String currentVideoId = "";
    private static long generation;

    private static final List<WeakReference<ViewGroup>> overlayCandidates = new ArrayList<>();
    private static boolean overlayPickScheduled;

    private LiveChatDanmakuPatch() {
    }

    /**
     * Injection point. Called when a new video starts playing.
     */
    public static void newVideoStarted(VideoInformation.PlaybackController ignoredPlayerController) {
        try {
            generation++;
            currentVideoId = "";
            stopPolling();
            if (overlay != null) {
                overlay.clear();
            }
        } catch (Exception ex) {
            Logger.printException(() -> "Danmaku newVideoStarted failure", ex);
        }
    }

    /**
     * Injection point. Called approximately once per second during playback.
     */
    public static void videoTimeChanged(long playbackTimeMs) {
        try {
            if (!Settings.LIVE_CHAT_DANMAKU.get()) {
                stopPolling();
                return;
            }
            if (!VideoInformation.isLiveStream()) {
                stopPolling();
                return;
            }
            String videoId = VideoInformation.getVideoId();
            if (videoId.isEmpty()) {
                return;
            }
            if (!videoId.equals(currentVideoId)) {
                currentVideoId = videoId;
                startPolling(videoId);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "Danmaku videoTimeChanged failure", ex);
        }
    }

    /**
     * Injection point. Called for every player overlay container.
     * The largest container wins, to avoid attaching danmaku to a
     * small controls overlay. Candidate names are logged in debug
     * builds so the correct container can be pinned for a target
     * YouTube version if this heuristic ever picks the wrong one.
     */
    public static void setPlayerOverlay(View overlayView, String overlayName) {
        try {
            Logger.printDebug(() -> "Danmaku overlay candidate: " + overlayName
                    + " view=" + overlayView);
            if (LOCK_MODE_OVERLAY_NAME.equals(overlayName)) {
                return;
            }
            if (!(overlayView instanceof ViewGroup group)) {
                return;
            }
            synchronized (overlayCandidates) {
                overlayCandidates.add(new WeakReference<>(group));
            }
            scheduleOverlayPick();
        } catch (Exception ex) {
            Logger.printException(() -> "Danmaku setPlayerOverlay failure", ex);
        }
    }

    /**
     * Future injection point for the in-app live chat renderer.
     * Not wired by the Kotlin patch yet; when it is, messages will
     * arrive here with the app's own session, including members-only
     * chat, and polling can be removed.
     */
    public static void onChatMessage(String text, String author, boolean superChat, int color) {
        try {
            if (overlay != null) {
                overlay.spawn(new DanmakuMessage(text, author, superChat, color));
            }
        } catch (Exception ex) {
            Logger.printException(() -> "Danmaku onChatMessage failure", ex);
        }
    }

    private static void scheduleOverlayPick() {
        if (overlayPickScheduled) {
            return;
        }
        overlayPickScheduled = true;
        MAIN.postDelayed(() -> {
            overlayPickScheduled = false;
            pickOverlay();
        }, OVERLAY_PICK_DELAY_MS);
    }

    private static void pickOverlay() {
        try {
            ViewGroup best = null;
            int bestArea = 0;
            synchronized (overlayCandidates) {
                for (WeakReference<ViewGroup> ref : new ArrayList<>(overlayCandidates)) {
                    ViewGroup candidate = ref.get();
                    if (candidate == null) {
                        continue;
                    }
                    int area = candidate.getWidth() * candidate.getHeight();
                    if (area > bestArea) {
                        bestArea = area;
                        best = candidate;
                    }
                }
                overlayCandidates.clear();
            }
            if (best == null) {
                return;
            }
            if (overlay == null) {
                overlay = new DanmakuOverlay(best.getContext());
            }
            overlay.attachTo(best);
        } catch (Exception ex) {
            Logger.printException(() -> "Danmaku pickOverlay failure", ex);
        }
    }

    private static void startPolling(String videoId) {
        stopPolling();
        pollingSource = new LiveChatPollingSource();
        pollingSource.start(videoId, message -> {
            if (overlay != null) {
                overlay.spawn(message);
            }
        });
    }

    private static void stopPolling() {
        if (pollingSource != null) {
            pollingSource.stop();
            pollingSource = null;
        }
    }
}
