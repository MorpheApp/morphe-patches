/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4929
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.returnyoutubedislike.requests.RYDVoteData;
import app.morphe.extension.shared.returnyoutubedislike.requests.ReturnYouTubeDislikeAPI;

/**
 * Removes upcoming songs of mixes and radio that have fewer likes than the configured minimum.
 * Like counts are fetched from Return YouTube Dislike, one song at a time on a background thread.
 * The queue is read and changed only on the executor of the app queue manager.
 */
@SuppressWarnings("unused")
public final class QueueLikeFilterPatch {

    /**
     * Implemented by the app queue manager during patching.
     */
    public interface QueueAccess {
        Executor patch_likeFilterExecutor();

        /**
         * Index of the current song in the main lane.
         */
        int patch_likeFilterCurrentIndex();

        List<?> patch_likeFilterLaneItems(int lane);

        @Nullable
        String patch_likeFilterVideoId(Object item);

        void patch_likeFilterRemove(Object item);

        @Nullable
        Object patch_likeFilterLane(int lane);

        void patch_likeFilterAddListener(Object lane, Object listener);
    }

    /**
     * Implements the app queue listener during patching. Every callback calls {@link #onQueueChanged()}.
     */
    public static final class QueueListener {
    }

    /**
     * The songs the user started or added. The current song is always in this lane.
     */
    private static final int MAIN_LANE = 0;

    /**
     * The autoplay songs that follow the main lane. A song started on its own, such as from the
     * search results, has its radio in this lane.
     */
    private static final int AUTOPLAY_LANE = 1;

    private static final int[] LANES = {MAIN_LANE, AUTOPLAY_LANE};

    /**
     * Number of upcoming songs that are checked.
     */
    private static final int UPCOMING_SONGS_TO_CHECK = 10;

    private static final long LOOKUP_INTERVAL_MILLISECONDS = 750;

    /**
     * Delay before checking again after a failed lookup. Return YouTube Dislike requests are
     * skipped while its own backoff after a connection error or rate limit is in effect.
     */
    private static final long LOOKUP_RETRY_DELAY_MILLISECONDS = 30_000;

    private static final long QUEUE_TASK_TIMEOUT_SECONDS = 5;

    private static final int VIDEO_ID_CACHE_SIZE = 500;

    /**
     * Mixes and radio have playlist IDs that start with this prefix.
     */
    private static final String RADIO_PLAYLIST_PREFIX = "RD";

    /**
     * The thread is created on first use, so no thread exists while the filter is off.
     */
    private static final ScheduledExecutorService worker =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "morphe-queue-like-filter");
                thread.setDaemon(true);
                return thread;
            });

    private static final AtomicBoolean scanScheduled = new AtomicBoolean();

    /**
     * Songs that were already checked with {@link #checkedMinimumLikes}.
     */
    private static final Set<String> checkedVideoIds = Collections.synchronizedSet(
            Collections.newSetFromMap(Utils.createSizeRestrictedMap(VIDEO_ID_CACHE_SIZE)));

    /**
     * Songs the user added with "Play next" or "Add to queue". These are never removed.
     */
    private static final Set<String> userQueuedVideoIds = Collections.synchronizedSet(
            Collections.newSetFromMap(Utils.createSizeRestrictedMap(VIDEO_ID_CACHE_SIZE)));

    /**
     * Lanes that have a {@link QueueListener}. Only used on the queue executor.
     */
    @SuppressWarnings("unchecked")
    private static final WeakReference<Object>[] listenedLanes = new WeakReference[LANES.length];

    private static volatile WeakReference<QueueAccess> queueRef = new WeakReference<>(null);

    /**
     * If a player response was seen. Until then the main lane is not changed.
     */
    private static volatile boolean playlistIdKnown;

    @Nullable
    private static volatile String playlistId;

    /**
     * Only used on the worker thread.
     */
    private static int checkedMinimumLikes = -1;

    /**
     * Injection point. Called when the app queue manager is created.
     */
    public static void setQueue(QueueAccess queue) {
        queueRef = new WeakReference<>(queue);
    }

    /**
     * Injection point. Called by every queue listener callback.
     */
    public static void onQueueChanged() {
        scheduleScan(0);
    }

    /**
     * Injection point. Called with the songs the user added to the queue, before they are added.
     */
    public static void onSongsEnqueued(@Nullable List<?> items) {
        try {
            if (items == null || !Settings.QUEUE_LIKE_FILTER.get()) {
                return;
            }

            QueueAccess queue = queueRef.get();
            if (queue == null) {
                return;
            }

            for (Object item : items) {
                String videoId = queue.patch_likeFilterVideoId(item);
                if (videoId != null) {
                    Logger.printDebug(() -> "Song added by the user: " + videoId);
                    userQueuedVideoIds.add(videoId);
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "onSongsEnqueued failure", ex);
        }
    }

    /**
     * Injection point. Called when another song starts playing.
     */
    public static void onVideoIdChanged(@Nullable String videoId) {
        scheduleScan(0);
    }

    /**
     * Injection point. Called with the video ID, playlist ID and playlist index of each player response.
     */
    public static void onPlaylistChanged(@Nullable String videoId,
                                         @Nullable String newPlaylistId,
                                         int playlistIndex) {
        if (!Settings.QUEUE_LIKE_FILTER.get()) {
            return;
        }

        playlistId = newPlaylistId;
        playlistIdKnown = true;
        scheduleScan(0);
    }

    private static void scheduleScan(long delayMilliseconds) {
        try {
            if (Settings.QUEUE_LIKE_FILTER.get() && scanScheduled.compareAndSet(false, true)) {
                worker.schedule(QueueLikeFilterPatch::scan, delayMilliseconds, TimeUnit.MILLISECONDS);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "scheduleScan failure", ex);
        }
    }

    /**
     * The main lane is filtered only for mixes and radio. Playlists and albums are not changed.
     * The autoplay lane is always filtered.
     */
    private static boolean isRadio() {
        if (!playlistIdKnown) {
            return false;
        }

        String id = playlistId;
        // A song started on its own has no playlist, and the app adds a radio of similar songs.
        return id == null || id.isEmpty() || id.startsWith(RADIO_PLAYLIST_PREFIX);
    }

    /**
     * Checks the upcoming songs one at a time, and removes the songs with too few likes.
     */
    private static void scan() {
        scanScheduled.set(false);

        try {
            QueueAccess queue = queueRef.get();
            if (queue == null) {
                return;
            }

            final int minimumLikes = Settings.QUEUE_LIKE_FILTER_MINIMUM_LIKES.get();
            if (minimumLikes != checkedMinimumLikes) {
                checkedMinimumLikes = minimumLikes;
                checkedVideoIds.clear();
            }

            while (Settings.QUEUE_LIKE_FILTER.get()) {
                String videoId = runOnQueueExecutor(queue, () -> {
                    listenToLanes(queue);
                    return nextUncheckedVideoId(queue);
                });
                if (videoId == null) {
                    return;
                }

                RYDVoteData votes = ReturnYouTubeDislikeAPI.fetchVotes(videoId);
                if (votes == null) {
                    scheduleScan(LOOKUP_RETRY_DELAY_MILLISECONDS);
                    return;
                }

                checkedVideoIds.add(videoId);

                final long likes = votes.getLikeCount();
                // Return YouTube Dislike reports no likes and no views for songs it has no data for.
                final boolean hasData = likes > 0 || votes.viewCount > 0;
                if (hasData && likes < minimumLikes && Settings.QUEUE_LIKE_FILTER.get()) {
                    final boolean removed = runOnQueueExecutor(queue,
                            () -> removeUpcomingSong(queue, videoId));
                    Logger.printDebug(() -> (removed ? "Removed " : "Could not remove ")
                            + videoId + " with " + likes + " likes");
                } else {
                    Logger.printDebug(() -> "Keeping " + videoId + " with " + likes + " likes");
                }

                //noinspection BusyWait
                Thread.sleep(LOOKUP_INTERVAL_MILLISECONDS);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "scan failure", ex);
        }
    }

    /**
     * Adds a listener to each queue lane, so changes of the queue start a new scan.
     */
    private static void listenToLanes(QueueAccess queue) {
        for (int lane : LANES) {
            Object laneObject = queue.patch_likeFilterLane(lane);
            WeakReference<Object> listened = listenedLanes[lane];
            if (laneObject == null || (listened != null && listened.get() == laneObject)) {
                continue;
            }

            queue.patch_likeFilterAddListener(laneObject, new QueueListener());
            listenedLanes[lane] = new WeakReference<>(laneObject);
        }
    }

    /**
     * The next songs in play order: the rest of the main lane if it is a mix or radio,
     * then the autoplay songs.
     */
    private static List<Object> upcomingSongs(QueueAccess queue) {
        List<Object> upcoming = new ArrayList<>(UPCOMING_SONGS_TO_CHECK);

        if (isRadio()) {
            final int currentIndex = queue.patch_likeFilterCurrentIndex();
            if (currentIndex >= 0) {
                addUpcoming(upcoming, queue.patch_likeFilterLaneItems(MAIN_LANE), currentIndex + 1);
            }
        }
        addUpcoming(upcoming, queue.patch_likeFilterLaneItems(AUTOPLAY_LANE), 0);

        return upcoming;
    }

    private static void addUpcoming(List<Object> upcoming, List<?> items, int startIndex) {
        for (int i = startIndex, size = items.size();
             i < size && upcoming.size() < UPCOMING_SONGS_TO_CHECK; i++) {
            upcoming.add(items.get(i));
        }
    }

    @Nullable
    private static String nextUncheckedVideoId(QueueAccess queue) {
        for (Object item : upcomingSongs(queue)) {
            String videoId = queue.patch_likeFilterVideoId(item);
            if (videoId != null
                    && !checkedVideoIds.contains(videoId)
                    && !userQueuedVideoIds.contains(videoId)) {
                return videoId;
            }
        }

        return null;
    }

    /**
     * Finds the song again in the current queue, so a queue that changed during the lookup
     * cannot remove a different song.
     */
    private static boolean removeUpcomingSong(QueueAccess queue, String videoId) {
        for (Object item : upcomingSongs(queue)) {
            if (videoId.equals(queue.patch_likeFilterVideoId(item))) {
                queue.patch_likeFilterRemove(item);
                return true;
            }
        }

        return false;
    }

    private static <T> T runOnQueueExecutor(QueueAccess queue, Callable<T> task) throws Exception {
        FutureTask<T> future = new FutureTask<>(task);
        queue.patch_likeFilterExecutor().execute(future);
        return future.get(QUEUE_TASK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }
}
