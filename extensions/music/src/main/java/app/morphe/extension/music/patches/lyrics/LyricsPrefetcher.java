/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics;

import androidx.annotation.Nullable;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import app.morphe.extension.music.patches.lyrics.model.Lyrics;
import app.morphe.extension.music.patches.lyrics.model.TrackInfo;
import app.morphe.extension.music.patches.lyrics.requests.LyricsProvider;
import app.morphe.extension.music.patches.lyrics.requests.LyricsRequests;
import app.morphe.extension.music.patches.lyrics.storage.LyricsCache;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * Fetches the lyrics of the track queued behind the current one, so the switch to it
 * finds them in the cache instead of waiting for the network.
 *
 * <p>The work runs on a thread of its own, which is the whole point of it: a prefetch
 * must not be able to delay, cancel or take part in the lookup of the track that is
 * actually playing. It therefore never touches the lookup request id, the in-flight
 * fetches or the state, and it only ever adds to the lyrics cache.
 *
 * <p>Only what the lookup itself would store is written, so a prefetch never decides
 * which provider the user gets: the cache is keyed by provider, and a track no
 * provider answered for keeps its miss, which leaves the usual search running when the
 * track is reached.
 */
final class LyricsPrefetcher {

    private static final ExecutorService executor = Executors.newSingleThreadExecutor(
            runnable -> {
                Thread thread = new Thread(runnable, "LyricsPrefetch");
                thread.setDaemon(true);
                thread.setPriority(Thread.MIN_PRIORITY);
                return thread;
            });

    /**
     * The video whose successor is being fetched, which is also the generation of the
     * running task: a newer track replaces it, and the older task stops at its next
     * checkpoint instead of fetching lyrics nobody asked for anymore.
     */
    private static volatile String prefetchingFor;

    private static volatile Future<?> running;

    private LyricsPrefetcher() {
    }

    /**
     * Starts a prefetch for whatever plays after the given video. Cheap to call on
     * every track: a target that is already being prefetched is left alone, and
     * everything that cannot answer is skipped without a request.
     */
    static void prefetchSuccessorOf(@Nullable String currentVideoId) {
        if (currentVideoId == null || currentVideoId.isEmpty()) return;
        if (!Settings.LYRICS_ENABLED.get()) return;
        if (!Utils.isNetworkConnected()) return;

        final Future<?> previous;
        synchronized (LyricsPrefetcher.class) {
            if (currentVideoId.equals(prefetchingFor)) return;
            prefetchingFor = currentVideoId;
            previous = running;
        }
        if (previous != null) {
            // Frees the single thread for the track that is playing now.
            previous.cancel(true);
        }
        final Future<?> task = executor.submit(() -> runPrefetch(currentVideoId));
        synchronized (LyricsPrefetcher.class) {
            if (currentVideoId.equals(prefetchingFor)) {
                running = task;
            }
        }
    }

    private static void runPrefetch(String currentVideoId) {
        try {
            prefetch(currentVideoId);
        } catch (Exception ex) {
            Logger.printException(() -> "runPrefetch failure", ex);
        } finally {
            synchronized (LyricsPrefetcher.class) {
                if (currentVideoId.equals(prefetchingFor)) {
                    prefetchingFor = null;
                    running = null;
                }
            }
        }
    }

    private static void prefetch(String currentVideoId) {
        if (superseded(currentVideoId)) return;

        final String[] nextVideoId = new String[1];
        TrackInfo track = SessionQueue.nextTrackOf(currentVideoId, nextVideoId);
        if (superseded(currentVideoId)) return;
        if (track == null) return;

        // A successor the queue names by the video that is already playing is a repeat,
        // which has nothing to prepare for. A successor it can only name by title and
        // artist has no video id, which is no reason to skip it: the providers keyed by
        // one are not asked here anyway.
        if (nextVideoId[0] != null && nextVideoId[0].equals(currentVideoId)) {
            return;
        }

        // The providers keyed by the video of the playing track only know that track,
        // so the successor is asked with the ones that search by title and artist.
        final List<LyricsProvider> providers = LyricsManager.searchableProviders(
                LyricsManager.providersInOrder(Settings.LYRICS_SOURCE.get()));
        if (providers.isEmpty()) return;
        if (allKnown(track, providers)) return;

        fetchIntoCache(currentVideoId, track, providers);
    }

    /**
     * Asks the providers in the order the user put them in and stops at the first
     * usable answer, which is the same rule the lookup follows within a stage.
     */
    private static void fetchIntoCache(String currentVideoId, TrackInfo track,
                                       List<LyricsProvider> providers) {
        for (LyricsProvider provider : providers) {
            if (superseded(currentVideoId)) return;

            // Custom terms were typed for one track and must never be stored as the
            // answer for the default terms of another.
            if (LyricsRequests.isCustomMatchMode()) return;

            try {
                LyricsProvider.FetchResult result = provider.fetch(track);
                if (result == null || result.lyrics() == null
                        || result.lyrics() == Lyrics.NOT_FOUND) {
                    continue;
                }
                Lyrics lyrics = result.lyrics();
                if (!LyricsManager.isValidLyrics(lyrics, track)) continue;

                LyricsCache.put(track, LyricsManager.cacheSourceFor(lyrics), lyrics, null);
                Logger.printDebug(() -> "Lyrics prefetched: " + track + " via " + provider.name());
                return;
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception ex) {
                Logger.printDebug(() -> "Lyrics prefetch failed: " + provider.name(), ex);
            }
        }
    }

    /** Whether every provider that could answer already has an answer stored. */
    private static boolean allKnown(TrackInfo track, List<LyricsProvider> providers) {
        for (LyricsProvider provider : providers) {
            if (LyricsCache.get(track, provider.name(), null) == null) return false;
        }
        return true;
    }

    private static boolean superseded(String currentVideoId) {
        return !currentVideoId.equals(prefetchingFor) || Thread.currentThread().isInterrupted();
    }
}
