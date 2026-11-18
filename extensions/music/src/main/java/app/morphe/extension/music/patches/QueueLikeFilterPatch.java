/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4929
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */


package app.morphe.extension.music.patches;

import android.content.SharedPreferences;
import android.os.SystemClock;

import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.StringRef;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.requests.Requester;
import app.morphe.extension.shared.requests.Route;
import app.morphe.extension.shared.settings.Setting;

/**
 * Removes upcoming queue songs that have fewer likes than the configured minimum.
 *
 * <p>The queue is native, so the patch installs the accessors below into YouTube Music's own
 * queue manager. All decisions are made here on a background thread, and every native queue
 * mutation runs on the queue's executor.
 */
@SuppressWarnings("unused")
public final class QueueLikeFilterPatch {

    /** Implemented by the patched queue manager. */
    public interface QueueAccess {
        @Nullable
        Executor patch_qlfExecutor();

        int patch_qlfCurrentIndex();

        @Nullable
        Object[] patch_qlfLaneItems(int lane);

        @Nullable
        String patch_qlfVideoId(Object item);

        void patch_qlfRemove(Object item);

        @Nullable
        Object patch_qlfLane(int lane);

        void patch_qlfAddListener(Object lane, Object listener);
    }

    /** The patch makes this implement the app's queue listener, every callback wakes the worker. */
    public static final class QueueListener {
    }

    private enum Decision {
        KEEP,
        REMOVE,
        /** No decision was reached. The song is kept, but the lookup is not cached. */
        PAUSE,
    }

    private static final class Lookup {
        final Decision decision;
        final long likes;

        Lookup(Decision decision, long likes) {
            this.decision = decision;
            this.likes = likes;
        }
    }

    private static final String RYD_API_URL = "https://returnyoutubedislikeapi.com/";
    private static final Route GET_VOTES_ROUTE =
            new Route(Route.Method.GET, "votes?videoId={video_id}");

    /** Auto-generated mixes and radio use playlist IDs starting with this prefix. */
    private static final String RADIO_PLAYLIST_PREFIX = "RD";

    /** The queue the user built or started. The current track is always in this lane. */
    private static final int MAIN_LANE = 0;

    /**
     * The "Autoplay" suggestions that follow the main queue. A song started on its own, for
     * example from the search results, keeps its generated radio here instead of in the main lane.
     */
    private static final int AUTOPLAY_LANE = 1;

    private static final int[] LANES = {MAIN_LANE, AUTOPLAY_LANE};

    /** Only the upcoming items ahead of the current track are checked. */
    private static final int WINDOW_SIZE = 10;

    /** Delay between two API lookups. */
    private static final long CHECK_INTERVAL_MS = 750L;

    /** RYD asked us to back off. */
    private static final long RATE_LIMIT_PAUSE_MS = 60_000L;

    /** A lookup failed for another reason. Try again after a short pause. */
    private static final long RETRY_PAUSE_MS = 30_000L;

    /** How often an idle worker rechecks the queue when no listener callback arrives. */
    private static final long IDLE_WAIT_MS = 1_000L;

    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 5_000;
    private static final long QUEUE_TASK_TIMEOUT_SECONDS = 5;

    private static final long DEFAULT_MINIMUM_LIKES = 1_000L;

    private static final int CHECKED_CACHE_SIZE = 500;

    /** Video IDs that are already known to be kept. */
    private static final Set<String> checkedVideoIds = Collections.synchronizedSet(
            Collections.newSetFromMap(Utils.createSizeRestrictedMap(CHECKED_CACHE_SIZE)));

    /**
     * Video IDs the user queued explicitly with "Play next". Those songs are intentional, so the
     * filter leaves them alone just like it never considers the currently playing track.
     */
    private static final Set<String> explicitlyQueuedVideoIds = Collections.synchronizedSet(
            Collections.newSetFromMap(Utils.createSizeRestrictedMap(CHECKED_CACHE_SIZE)));

    private static final Object wakeLock = new Object();
    private static final AtomicBoolean workerStarted = new AtomicBoolean();
    private static final AtomicBoolean settingsListenerRegistered = new AtomicBoolean();

    // SharedPreferences holds listeners weakly, so retain ours for the patch lifetime.
    private static final SharedPreferences.OnSharedPreferenceChangeListener SETTINGS_LISTENER =
            (preferences, key) -> {
                if (!Settings.QUEUE_LIKE_FILTER_ENABLED.key.equals(key)) {
                    return;
                }

                // Let the settings cache update before starting or stopping the worker.
                Utils.runOnMainThread(QueueLikeFilterPatch::wakeWorkerIfEnabled);
            };

    private static volatile WeakReference<QueueAccess> queue = new WeakReference<>(null);
    @SuppressWarnings("unchecked")
    private static final WeakReference<Object>[] listenedLanes = new WeakReference[]{
            new WeakReference<>(null), new WeakReference<>(null)};
    private static volatile String currentPlaylistId;
    private static volatile boolean playlistIdKnown;
    private static volatile long pauseUntil;
    private static volatile long knownMinimumLikes = -1;
    private static volatile boolean announcedActive;
    private static volatile boolean announcedLookupFailure;

    private QueueLikeFilterPatch() {
    }

    /**
     * Injection point. Called from the queue manager constructor.
     */
    public static void capture(QueueAccess access) {
        queue = new WeakReference<>(access);
        wakeWorkerIfEnabled();
    }

    /**
     * Injection point. Called by every queue listener callback.
     */
    public static void onQueueChanged() {
        wakeWorkerIfEnabled();
    }

    /**
     * Injection point. Called before songs enqueued with "Play next" are inserted into the queue.
     */
    public static void onPlayNextEnqueued(@Nullable List<?> items) {
        if (items == null) {
            return;
        }

        QueueAccess access = queue.get();
        if (access == null) {
            return;
        }

        for (Object item : items) {
            String videoId = readVideoId(access, item);
            if (videoId != null) {
                explicitlyQueuedVideoIds.add(videoId);
                Logger.printDebug(() -> "queue like filter: ignoring play next video " + videoId);
            }
        }
    }

    /**
     * Injection point. Called when YouTube Music starts playing another video.
     */
    public static void onVideoIdChanged(@Nullable String videoId) {
        wakeWorkerIfEnabled();
    }

    /**
     * Injection point. Called with the video ID, playlist ID and playlist index of the current
     * player response.
     */
    public static void onPlaylistChanged(
            @Nullable String videoId,
            @Nullable String playlistId,
            int playlistIndex
    ) {
        // Keep this current even while disabled, so enabling the filter mid-track knows whether
        // the queue is an auto-generated mix.
        final String previousPlaylistId = currentPlaylistId;
        currentPlaylistId = playlistId;
        playlistIdKnown = true;
        if (!Objects.equals(previousPlaylistId, playlistId)) {
            explicitlyQueuedVideoIds.clear();
        }
        Logger.printDebug(() -> "queue like filter: playlist " + playlistId
                + (isRadioPlaylist() ? " (radio/mix)" : " (not filtered)"));
        wakeWorkerIfEnabled();
    }

    /**
     * Only auto-generated mixes and radio are filtered in the main lane. User playlists, albums and
     * other user-owned queues keep their items. The autoplay lane is always auto-generated.
     */
    private static boolean isRadioPlaylist() {
        // Nothing is known until the app reports a player response. Avoid touching a restored queue
        // before it has been identified.
        if (!playlistIdKnown) {
            return false;
        }

        String playlistId = currentPlaylistId;
        // A standalone song (for example one started from the search results) has no playlist ID,
        // and YouTube Music builds an auto-generated radio around it.
        return playlistId == null || playlistId.isEmpty()
                || playlistId.startsWith(RADIO_PLAYLIST_PREFIX);
    }

    private static void registerSettingsListener() {
        if (Utils.getContext() == null) {
            return;
        }
        if (!settingsListenerRegistered.compareAndSet(false, true)) {
            return;
        }

        try {
            Setting.preferences.preferences.registerOnSharedPreferenceChangeListener(
                    SETTINGS_LISTENER);
        } catch (Exception ex) {
            settingsListenerRegistered.set(false);
            Logger.printInfo(() -> "queue like filter could not listen for setting changes", ex);
        }
    }

    /**
     * Starts and wakes the worker only when the filter is enabled. When it is off, no worker
     * thread exists.
     */
    private static void wakeWorkerIfEnabled() {
        try {
            if (Utils.getContext() == null) {
                return;
            }

            registerSettingsListener();

            if (!isEnabled()) {
                return;
            }

            if (workerStarted.compareAndSet(false, true)) {
                Thread thread = new Thread(
                        QueueLikeFilterPatch::runLoop, "morphe-queue-like-filter");
                thread.setDaemon(true);
                thread.start();
            }
            synchronized (wakeLock) {
                wakeLock.notifyAll();
            }
        } catch (Exception | LinkageError ex) {
            Logger.printException(() -> "queue like filter start failure", ex);
        }
    }

    private static boolean canRun() {
        return Utils.getContext() != null && isEnabled();
    }

    private static void waitForWake(long timeoutMs) {
        synchronized (wakeLock) {
            try {
                wakeLock.wait(timeoutMs);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void runLoop() {
        while (true) {
            try {
                if (!canRun()) {
                    workerStarted.set(false);
                    // Close the race where the setting was enabled while the worker was exiting.
                    if (canRun()) {
                        wakeWorkerIfEnabled();
                    }
                    return;
                }

                QueueAccess access = queue.get();
                if (access == null) {
                    waitForWake(IDLE_WAIT_MS);
                    continue;
                }

                listenToQueue(access);

                if (!scanWindow(access)) {
                    waitForWake(IDLE_WAIT_MS);
                }
            } catch (Exception | LinkageError ex) {
                // A setting or library class failed to initialize. Keep the worker alive and log
                // it, otherwise a single early failure would silently disable the whole feature.
                Logger.printException(() -> "queue like filter loop failure", ex);
                waitForWake(IDLE_WAIT_MS);
            }
        }
    }

    private static void announceActiveOnce() {
        if (announcedActive) {
            return;
        }
        announcedActive = true;
        Logger.printInfo(() -> "queue like filter: active");
        Utils.showToastShort(StringRef.str("morphe_music_queue_like_filter_active_toast"));
    }

    /**
     * Attaches the native queue listener once per lane, so list changes and radio page loads wake
     * the worker instead of waiting for the next poll.
     */
    private static void listenToQueue(QueueAccess access) {
        try {
            Executor executor = access.patch_qlfExecutor();
            if (executor == null) {
                return;
            }

            runOnQueueExecutor(executor, () -> {
                for (int laneIndex : LANES) {
                    Object lane = access.patch_qlfLane(laneIndex);
                    if (lane == null || lane == listenedLanes[laneIndex].get()) {
                        continue;
                    }

                    access.patch_qlfAddListener(lane, new QueueListener());
                    listenedLanes[laneIndex] = new WeakReference<>(lane);
                    Logger.printDebug(() -> "queue like filter: listening to queue lane " + laneIndex);
                }
            });
        } catch (Exception ex) {
            Logger.printInfo(() -> "queue like filter could not listen to the queue", ex);
        }
    }

    /**
     * The next {@link #WINDOW_SIZE} items in play order: the rest of the main lane when it is an
     * auto-generated radio, then the autoplay suggestions.
     */
    private static List<Object> upcomingItems(QueueAccess access) {
        List<Object> upcoming = new ArrayList<>(WINDOW_SIZE);

        if (isRadioPlaylist()) {
            Object[] items = access.patch_qlfLaneItems(MAIN_LANE);
            final int currentIndex = access.patch_qlfCurrentIndex();
            if (currentIndex >= 0) {
                appendUpcoming(upcoming, items, currentIndex + 1);
            }
        }

        appendUpcoming(upcoming, access.patch_qlfLaneItems(AUTOPLAY_LANE), 0);
        return upcoming;
    }

    private static void appendUpcoming(
            List<Object> out,
            @Nullable Object[] items,
            int startIndex
    ) {
        if (items == null) {
            return;
        }
        for (int i = startIndex; i < items.length && out.size() < WINDOW_SIZE; i++) {
            out.add(items[i]);
        }
    }

    @Nullable
    private static String readVideoId(QueueAccess access, @Nullable Object item) {
        if (item == null) {
            return null;
        }
        try {
            String videoId = access.patch_qlfVideoId(item);
            return (videoId == null || videoId.isEmpty()) ? null : videoId;
        } catch (Exception ex) {
            // Some queue entries are not playable media items.
            Logger.printDebug(() -> "queue like filter: could not read video ID", ex);
            return null;
        }
    }

    /**
     * Checks the current ten upcoming slots, one song at a time.
     *
     * @return true if at least one API lookup was made, so the caller can immediately rescan the
     *         shifted window instead of waiting for a queue change.
     */
    private static boolean scanWindow(QueueAccess access) {
        if (upcomingItems(access).isEmpty()) {
            return false;
        }

        announceActiveOnce();

        if (pauseIfNeeded()) {
            return true;
        }

        final long minimum = minimumLikes();
        if (minimum != knownMinimumLikes) {
            knownMinimumLikes = minimum;
            checkedVideoIds.clear();
        }

        boolean checkedSomething = false;

        for (int slot = 0; slot < WINDOW_SIZE; slot++) {
            if (!isEnabled()) {
                return true;
            }

            // Re-read the snapshot for every slot. The queue can shift after a removal, and the
            // current index or source can change while a network request is in flight.
            List<Object> items = upcomingItems(access);
            if (slot >= items.size()) {
                break;
            }

            String videoId = readVideoId(access, items.get(slot));
            if (videoId == null
                    || explicitlyQueuedVideoIds.contains(videoId)
                    || checkedVideoIds.contains(videoId)) {
                continue;
            }

            final int upcomingSlot = slot;
            Logger.printInfo(() -> "queue like filter: checking " + videoId
                    + " at upcoming slot " + upcomingSlot);

            Lookup lookup = lookup(videoId);
            if (lookup.decision == Decision.PAUSE) {
                pauseIfNeeded();
                return true;
            }

            if (!isEnabled()) {
                return true;
            }

            checkedSomething = true;

            if (lookup.decision == Decision.REMOVE) {
                if (removeByVideoId(access, videoId)) {
                    Logger.printInfo(() -> "queue like filter: removed " + videoId
                            + " with " + lookup.likes + " likes");
                    Utils.showToastShort(StringRef.str(
                            "morphe_music_queue_like_filter_removed_toast", lookup.likes));
                } else {
                    // The queue may have moved on. Do not retry this song on every pass.
                    Logger.printInfo(() -> "queue like filter: could not remove " + videoId);
                    checkedVideoIds.add(videoId);
                }

                sleep(CHECK_INTERVAL_MS);
                return true;
            }

            checkedVideoIds.add(videoId);
            Logger.printInfo(() -> "queue like filter: keeping " + videoId
                    + " with likes=" + lookup.likes);
            sleep(CHECK_INTERVAL_MS);
        }

        return checkedSomething;
    }

    /**
     * Removes the upcoming item with the given video ID. Re-reading the queue immediately before
     * locating the item keeps shifted positions from deleting a different song.
     */
    private static boolean removeByVideoId(QueueAccess access, String videoId) {
        Executor executor = access.patch_qlfExecutor();
        if (executor == null) {
            return false;
        }

        try {
            final boolean[] removed = {false};
            runOnQueueExecutor(executor, () -> {
                // Do not remove anything if the filter was disabled. If the user switched to a
                // playlist or album, its main lane items are no longer part of the window.
                if (!isEnabled()) {
                    return;
                }

                for (Object item : upcomingItems(access)) {
                    if (videoId.equals(readVideoId(access, item))) {
                        access.patch_qlfRemove(item);
                        removed[0] = true;
                        return;
                    }
                }
            });
            return removed[0];
        } catch (Exception ex) {
            Logger.printInfo(() -> "queue like filter: remove failed for " + videoId, ex);
            return false;
        }
    }

    private static Lookup lookup(String videoId) {
        HttpURLConnection connection = null;
        try {
            connection = Requester.getConnectionFromRoute(RYD_API_URL, GET_VOTES_ROUTE, videoId);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            // Match the existing Return YouTube Dislike client as closely as possible.
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Connection", "keep-alive");
            connection.setRequestProperty("Pragma", "no-cache");
            connection.setRequestProperty("Cache-Control", "no-cache");
            connection.setUseCaches(false);

            final int responseCode = connection.getResponseCode();
            if (responseCode == Requester.HTTP_STATUS_CODE_TOO_MANY_REQUESTS) {
                pauseUntil = SystemClock.uptimeMillis() + RATE_LIMIT_PAUSE_MS;
                Logger.printInfo(() -> "queue like filter: rate limited, pausing 60 seconds");
                return new Lookup(Decision.PAUSE, -1);
            }

            if (responseCode != Requester.HTTP_STATUS_CODE_SUCCESS) {
                // 404 and other server responses are reachable answers. Keep the song, and do not
                // hammer the API for the same video again.
                Logger.printInfo(() -> "queue like filter: HTTP " + responseCode + " for " + videoId);
                return new Lookup(Decision.KEEP, -1);
            }

            JSONObject json = Requester.parseJSONObjectAndDisconnect(connection);
            connection = null;

            final long likes = json.optLong("likes", -1L);
            final long views = json.optLong("viewCount", -1L);

            // Negative means the API did not report the value. Not indexed yet means 0 likes and
            // 0 views. Both are treated as "keep".
            if (likes < 0 || (likes == 0 && views == 0)) {
                return new Lookup(Decision.KEEP, likes < 0 ? -1 : 0);
            }

            return new Lookup(likes < minimumLikes() ? Decision.REMOVE : Decision.KEEP, likes);
        } catch (Exception ex) {
            // Keep the song, but do not cache the failure. A transient network problem must not
            // permanently disable filtering for the rest of the queue.
            Logger.printInfo(() -> "queue like filter: lookup failed for " + videoId, ex);
            if (!announcedLookupFailure) {
                announcedLookupFailure = true;
                Utils.showToastLong(StringRef.str(
                        "morphe_music_queue_like_filter_network_toast"));
            }
            pauseUntil = SystemClock.uptimeMillis() + RETRY_PAUSE_MS;
            return new Lookup(Decision.PAUSE, -1);
        } finally {
            if (connection != null) {
                try {
                    connection.disconnect();
                } catch (Exception ex) {
                    Logger.printDebug(() -> "queue like filter: disconnect failed", ex);
                }
            }
        }
    }

    /**
     * Waits out an API backoff in short chunks so disabling the filter stops the wait promptly.
     */
    private static boolean pauseIfNeeded() {
        final long deadline = pauseUntil;
        if (deadline == 0 || SystemClock.uptimeMillis() >= deadline) {
            return false;
        }

        Logger.printInfo(() -> "queue like filter: waiting for the API backoff to expire");
        while (isEnabled()) {
            final long remaining = deadline - SystemClock.uptimeMillis();
            if (remaining <= 0) {
                break;
            }
            sleep(Math.min(remaining, IDLE_WAIT_MS));
        }
        return true;
    }

    private static boolean isEnabled() {
        try {
            return Settings.QUEUE_LIKE_FILTER_ENABLED.get();
        } catch (Exception ex) {
            Logger.printDebug(() -> "queue like filter: could not read the enabled setting", ex);
            return false;
        }
    }

    private static long minimumLikes() {
        try {
            String raw = Settings.QUEUE_LIKE_FILTER_MIN_LIKES.get();
            if (raw != null) {
                return Math.max(0, Long.parseLong(raw.trim()));
            }
        } catch (Exception ex) {
            Logger.printDebug(() -> "queue like filter: using the default minimum likes", ex);
        }
        return DEFAULT_MINIMUM_LIKES;
    }

    /** Native queue mutations only ever run on YouTube Music's own queue executor. */
    private static void runOnQueueExecutor(Executor executor, Runnable task)
            throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        executor.execute(() -> {
            try {
                task.run();
            } catch (Exception ex) {
                Logger.printException(() -> "queue like filter queue task failed", ex);
            } finally {
                latch.countDown();
            }
        });
        if (!latch.await(QUEUE_TASK_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            Logger.printDebug(() -> "queue like filter: queue task timed out");
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}