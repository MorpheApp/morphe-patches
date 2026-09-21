package app.morphe.extension.youtube.series;

import static app.morphe.extension.youtube.series.TrackerModels.*;

import android.content.Context;
import android.database.sqlite.*;
import android.os.Handler;
import android.os.Looper;

import app.morphe.extension.shared.Logger;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** One ordered mutation stream, two bounded network workers, callbacks on the UI thread. */
public final class TrackerService {
    private static volatile TrackerService instance;

    public static synchronized TrackerService get(Context context) {
        if (instance == null) instance = new TrackerService(context.getApplicationContext());
        return instance;
    }

    static TrackerService existing() {
        return instance;
    }

    final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService storage;
    private final ExecutorService network;
    private final TrackerRepository repository;
    private final CatalogClient catalogs;
    private final PlaylistDiscovery discovery =
            new PlaylistDiscovery(
                    CatalogClient::request,
                    android.os.SystemClock::elapsedRealtime,
                    error -> Logger.printException(() -> "Playlist discovery", error));
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final ArrayDeque<Mutation> pending = new ArrayDeque<>(); // storage thread only
    private final Map<String, Future<?>> refreshing = new HashMap<>();
    private final Map<String, TrackerRepository.FetchTicket> refreshTickets = new HashMap<>();
    private final ArrayDeque<String> quietQueue = new ArrayDeque<>();
    private volatile String epoch, storageError = "";
    private volatile TrackerRepository.Undo bulkUndo;
    private long lastPrune;
    private volatile Set<String> trackedVideos = Collections.emptySet();

    private static final class Mutation {
        final Runnable work, success;
        final Consumer<String> error;

        Mutation(Runnable work, Runnable success, Consumer<String> error) {
            this.work = work;
            this.success = success;
            this.error = error;
        }
    }

    private TrackerService(Context context) {
        this(
                new TrackerRepository(context),
                new CatalogClient(),
                new ThreadPoolExecutor(
                        1,
                        1,
                        0,
                        TimeUnit.MILLISECONDS,
                        new ArrayBlockingQueue<>(256),
                        r -> new Thread(r, "SeriesTracker-store")),
                new ThreadPoolExecutor(
                        2,
                        2,
                        0,
                        TimeUnit.MILLISECONDS,
                        new ArrayBlockingQueue<>(16),
                        r -> new Thread(r, "SeriesTracker-catalog")));
    }

    TrackerService(
            TrackerRepository repository,
            CatalogClient catalogs,
            ExecutorService storage,
            ExecutorService network) {
        this.repository = repository;
        this.catalogs = catalogs;
        this.storage = storage;
        this.network = network;
        submit(
                () -> {
                    try {
                        initializeStorage();
                    } catch (RuntimeException failure) {
                        storageError = "series_tracker_error_storage_unavailable";
                        Logger.printException(() -> storageError, failure);
                    }
                    changed();
                });
    }

    private void initializeStorage() {
        String restoredEpoch = repository.historyEpoch();
        repository.recoverInterruptedFetches();
        updateTrackedVideos();
        epoch = restoredEpoch;
    }

    public void listen(Runnable listener) {
        listeners.add(listener);
    }

    public void unlisten(Runnable listener) {
        listeners.remove(listener);
    }

    private void changed() {
        main.post(
                () -> {
                    for (Runnable listener : listeners) listener.run();
                });
    }

    private boolean submit(Runnable work) {
        try {
            storage.execute(work);
            return true;
        } catch (RejectedExecutionException failure) {
            return false;
        }
    }

    private void busy(Consumer<String> error) {
        main.post(() -> error.accept("series_tracker_error_storage_busy"));
    }

    private void mutation(Runnable work, Runnable success, Consumer<String> error) {
        Mutation command = new Mutation(work, success, error);
        if (!submit(() -> accept(command, false))) busy(error);
    }

    private void accept(Mutation command, boolean completion) {
        if (!storageError.isEmpty()) {
            // Network completions are already accepted work, bounded by the network pool.
            if (completion || pending.size() < 256) pending.add(command);
            else main.post(() -> command.error.accept("series_tracker_error_pending_full"));
            return;
        }
        execute(command);
    }

    private void completion(Mutation command) {
        // A full storage queue must not strand a finished refresh in 'loading'.
        if (!submit(() -> accept(command, true))) main.postDelayed(() -> completion(command), 250);
    }

    private boolean execute(Mutation command) {
        try {
            command.work.run();
            main.post(command.success);
            changed();
            return true;
        } catch (SQLiteConstraintException failure) {
            main.post(() -> command.error.accept("series_tracker_error_conflict"));
            return true;
        } catch (SQLiteException failure) {
            storageError = "series_tracker_error_storage_save";
            Logger.printException(() -> storageError, failure);
            pending.addFirst(command);
            main.post(() -> command.error.accept(storageError));
            changed();
            return false;
        } catch (RuntimeException failure) {
            main.post(() -> command.error.accept(message(failure)));
            return true;
        }
    }

    public void retryStorage(Consumer<String> error) {
        if (!submit(
                () -> {
                    try {
                        if (epoch == null) initializeStorage();
                        storageError = "";
                        while (!pending.isEmpty()) {
                            Mutation next = pending.removeFirst();
                            if (!execute(next)) return;
                        }
                        changed();
                    } catch (RuntimeException failure) {
                        storageError = "series_tracker_error_storage_unavailable";
                        main.post(() -> error.accept(storageError));
                        changed();
                    }
                })) busy(error);
    }

    public String storageError() {
        return storageError;
    }

    boolean isTracked(String videoId) {
        return trackedVideos.contains(videoId);
    }

    private void updateTrackedVideos() {
        repository.prune(System.currentTimeMillis());
        trackedVideos = repository.trackedVideoIds();
    }

    public boolean canUndo() {
        return bulkUndo != null;
    }

    public <T> void read(Supplier<T> work, Consumer<T> done, Consumer<String> error) {
        if (!submit(
                () -> {
                    try {
                        T value = work.get();
                        main.post(() -> done.accept(value));
                    } catch (RuntimeException failure) {
                        main.post(() -> error.accept(message(failure)));
                    }
                })) busy(error);
    }

    private boolean syncingYouTube;
    private long lastYouTubeAttempt;
    private final List<Runnable> syncWaiters = new ArrayList<>();
    private String syncStatus = "";

    String syncStatus() {
        return RecordingPrivacy.allowsSync() ? syncStatus : "";
    }

    /** UI-thread entry point; coalesces navigation, foregrounding and Continue checks. */
    public void syncYouTube(boolean force, Runnable done) {
        if (!RecordingPrivacy.allowsSync()) {
            done.run();
            return;
        }
        long now = android.os.SystemClock.elapsedRealtime();
        if (syncingYouTube) {
            syncWaiters.add(done);
            return;
        }
        if (!force && lastYouTubeAttempt > 0 && now - lastYouTubeAttempt < 30_000) {
            done.run();
            return;
        }
        syncingYouTube = true;
        syncStatus = force ? "series_tracker_sync_checking" : "";
        lastYouTubeAttempt = now;
        syncWaiters.add(done);
        changed();
        long generation = RecordingPrivacy.generation();
        if (!submit(
                () -> {
                    try {
                        String capturedEpoch = repository.historyEpoch();
                        long revision = repository.manualRevision();
                        List<Series> snapshot = repository.library();
                        network.execute(
                                () -> {
                                    try {
                                        NativeProgressSync.Result result =
                                                NativeProgressSync.fetch(
                                                        snapshot,
                                                        generation,
                                                        app.morphe.extension.youtube.settings
                                                                .Settings
                                                                .SERIES_TRACKER_COMPLETION_PERCENT
                                                                .get(),
                                                        app.morphe.extension.youtube.settings
                                                                .Settings
                                                                .SERIES_TRACKER_COMPLETION_SECONDS
                                                                .get(),
                                                        force);
                                        if (!submit(
                                                () -> {
                                                    try {
                                                        if (RecordingPrivacy.acceptsSync(
                                                                generation))
                                                            repository.mergeRemote(
                                                                    capturedEpoch,
                                                                    revision,
                                                                    snapshot,
                                                                    result,
                                                                    System.currentTimeMillis());
                                                        finishYouTubeSync(
                                                                result.complete
                                                                        ? ""
                                                                        : "series_tracker_sync_partial");
                                                    } catch (RuntimeException failure) {
                                                        finishYouTubeSync(
                                                                "series_tracker_sync_failed");
                                                    }
                                                })) finishYouTubeSync("series_tracker_sync_failed");
                                    } catch (Exception failure) {
                                        finishYouTubeSync("series_tracker_sync_failed");
                                    }
                                });
                    } catch (RuntimeException failure) {
                        finishYouTubeSync("series_tracker_sync_failed");
                    }
                })) finishYouTubeSync("series_tracker_sync_failed");
    }

    private void finishYouTubeSync(String status) {
        main.post(
                () -> {
                    syncingYouTube = false;
                    syncStatus = status;
                    List<Runnable> callbacks = new ArrayList<>(syncWaiters);
                    syncWaiters.clear();
                    changed();
                    for (Runnable callback : callbacks) callback.run();
                });
    }

    public void library(Consumer<List<Series>> done, Consumer<String> error) {
        read(repository::library, done, error);
    }

    public void series(String id, Consumer<Series> done, Consumer<String> error) {
        read(() -> repository.series(id), done, error);
    }

    void checkpoint(
            PlaybackReducer.Snapshot snapshot, long privacyGeneration, Runnable acknowledged) {
        String capturedEpoch = epoch;
        mutation(
                () -> {
                    if (RecordingPrivacy.accepts(privacyGeneration)
                            && repository.checkpoint(
                                    snapshot, capturedEpoch == null ? epoch : capturedEpoch)) {
                        if (System.currentTimeMillis() - lastPrune > 3600000) {
                            repository.prune(System.currentTimeMillis());
                            lastPrune = System.currentTimeMillis();
                        }
                    }
                },
                acknowledged,
                error -> {});
    }

    public Future<?> discover(
            String video, Consumer<List<PlaylistDiscovery.Match>> done, Consumer<String> error) {
        try {
            return network.submit(
                    () -> {
                        try {
                            List<PlaylistDiscovery.Match> matches = discovery.find(video);
                            main.post(() -> done.accept(matches));
                        } catch (Exception failure) {
                            main.post(() -> error.accept("series_tracker_discovery_unavailable"));
                        }
                    });
        } catch (RejectedExecutionException failure) {
            main.post(() -> error.accept("series_tracker_error_catalog_busy"));
            return null;
        }
    }

    public Future<?> preview(
            String id, Consumer<CatalogClient.Catalog> done, Consumer<String> error) {
        try {
            return network.submit(
                    () -> {
                        try {
                            CatalogClient.Catalog result = catalogs.fetch(id);
                            main.post(() -> done.accept(result));
                        } catch (Exception failure) {
                            main.post(() -> error.accept(message(failure)));
                        }
                    });
        } catch (RejectedExecutionException failure) {
            main.post(() -> error.accept("series_tracker_error_catalog_busy"));
            return null;
        }
    }

    public void save(
            String id,
            String name,
            String bookmark,
            CatalogClient.Catalog catalog,
            Runnable done,
            Consumer<String> error) {
        save(id, name, bookmark, catalog, "", done, error);
    }

    public void save(
            String id,
            String name,
            String bookmark,
            CatalogClient.Catalog catalog,
            String startVideo,
            Runnable done,
            Consumer<String> error) {
        mutation(
                () -> {
                    repository.saveSeries(
                            id, name, bookmark, catalog, startVideo, System.currentTimeMillis());
                    updateTrackedVideos();
                },
                done,
                error);
    }

    void saveFollow(
            String id,
            String name,
            CatalogClient.Catalog catalog,
            FollowStart selection,
            long privacyGeneration,
            Runnable done,
            Consumer<String> error) {
        mutation(
                () -> {
                    if (selection.positionMs >= 0
                            && !RecordingPrivacy.acceptsManualPosition(privacyGeneration))
                        throw new IllegalStateException("series_tracker_identity_unavailable");
                    repository.saveSeries(
                            id, name, "", catalog, selection, System.currentTimeMillis());
                    updateTrackedVideos();
                },
                done,
                error);
    }

    /** Called only when the Series page becomes visible, never by a playback/UI update. */
    public void refreshStale() {
        submit(
                () -> {
                    if (!storageError.isEmpty()) return;
                    try {
                        long now = System.currentTimeMillis();
                        for (Series s : repository.library())
                            if (!refreshing.containsKey(s.id)
                                    && !quietQueue.contains(s.id)
                                    && CatalogRefreshPolicy.due(
                                            repository.hasEpisodeInfo(s.id) ? s.fetchedAt : 0,
                                            repository.lastRefreshAttempt(s.id),
                                            repository.refreshRetryAt(s.id),
                                            now)) quietQueue.add(s.id);
                        pumpQuiet();
                    } catch (RuntimeException failure) {
                        Logger.printException(() -> "Could not check catalog freshness", failure);
                    }
                });
    }

    private void pumpQuiet() {
        if (!storageError.isEmpty() || refreshing.size() >= 2 || quietQueue.isEmpty()) return;
        String id = quietQueue.removeFirst();
        // Start each catalog in its own mutation, so a failure cannot retry a
        // different catalog's already-committed completion or lose its ticket.
        mutation(
                () -> beginFetch(id, true, message -> {}),
                () -> submit(this::pumpQuiet),
                error -> submit(this::pumpQuiet));
    }

    public void refresh(String id, Consumer<String> error) {
        mutation(
                () -> {
                    quietQueue.remove(id);
                    beginFetch(id, false, error);
                },
                () -> {},
                error);
    }

    private void beginFetch(String id, boolean quiet, Consumer<String> error) {
        if (refreshing.containsKey(id)) return;
        long now = System.currentTimeMillis(), remaining = repository.refreshRetryAt(id) - now;
        if (remaining > 0) {
            if (!quiet) main.post(() -> error.accept("series_tracker_error_retry_later"));
            return;
        }
        TrackerRepository.FetchTicket ticket = repository.beginRefresh(id);
        repository.refreshAttempt(id, now);
        refreshTickets.put(id, ticket);
        try {
            Future<?> future = network.submit(() -> fetchAndPublish(ticket, quiet, error));
            refreshing.put(id, future);
        } catch (RejectedExecutionException failure) {
            refreshTickets.remove(id);
            repository.fetchFailed(ticket, "series_tracker_error_catalog_busy");
            repository.refreshRetry(id, now + CatalogRefreshPolicy.RETRY_MS);
        }
    }

    private void fetchAndPublish(
            TrackerRepository.FetchTicket ticket, boolean quiet, Consumer<String> error) {
        CatalogClient.Catalog result = null;
        Exception failure = null;
        try {
            result = catalogs.fetch(ticket.id);
        } catch (Exception e) {
            failure = e;
        }
        CatalogClient.Catalog ready = result;
        Exception problem = failure;
        completion(
                new Mutation(
                        () -> publishFetch(ticket, ready, problem, quiet),
                        () -> submit(this::pumpQuiet),
                        error));
    }

    private void publishFetch(
            TrackerRepository.FetchTicket ticket,
            CatalogClient.Catalog catalog,
            Exception failure,
            boolean quiet) {
        String id = ticket.id;
        if (refreshTickets.get(id) != ticket) return;
        // Keep ownership until the database commit succeeds, including a storage retry.
        if (failure == null) {
            repository.publish(ticket, catalog, System.currentTimeMillis());
            updateTrackedVideos();
            repository.refreshRetry(id, 0);
        } else {
            long serverDelay =
                    failure instanceof CatalogClient.Failure
                            ? ((CatalogClient.Failure) failure).retryAfterMs
                            : 0;
            long delay = Math.max(quiet ? CatalogRefreshPolicy.RETRY_MS : 30000, serverDelay);
            repository.fetchFailed(ticket, message(failure));
            repository.refreshRetry(id, System.currentTimeMillis() + delay);
        }
        refreshTickets.remove(id);
        refreshing.remove(id);
    }

    public void startHere(Series s, Episode e, Runnable done, Consumer<String> error) {
        mutation(
                () -> {
                    requireCurrent(s);
                    repository.startHere(
                            s.id, s.revision, e.ordinal, e.videoId, System.currentTimeMillis());
                    updateTrackedVideos();
                },
                done,
                error);
    }

    public void acknowledgeEpisodes(String id) {
        mutation(() -> repository.acknowledgeEpisodes(id), () -> {}, message -> {});
    }

    private void requireCurrent(Series s) {
        if (!repository.series(s.id).epoch.equals(s.epoch))
            throw new IllegalStateException("series_tracker_error_series_changed");
    }

    public void select(
            Series s,
            String videoId,
            int ordinal,
            boolean restart,
            Runnable done,
            Consumer<String> error) {
        mutation(
                () -> {
                    requireCurrent(s);
                    repository.select(s.id, s.revision, ordinal, videoId, restart);
                    updateTrackedVideos();
                },
                done,
                error);
    }

    public void mark(
            String videoId, TrackerModels.Override value, Runnable done, Consumer<String> error) {
        mutation(() -> repository.mark(videoId, value, System.currentTimeMillis()), done, error);
    }

    public void through(Series s, int ordinal, Runnable done, Consumer<String> error) {
        mutation(
                () -> {
                    requireCurrent(s);
                    bulkUndo =
                            repository.markThrough(
                                    s.id, s.revision, ordinal, System.currentTimeMillis());
                },
                done,
                error);
    }

    public void undo(Runnable done, Consumer<String> error) {
        TrackerRepository.Undo undo = bulkUndo;
        if (undo == null) return;
        mutation(
                () -> {
                    repository.undo(undo, System.currentTimeMillis());
                    if (bulkUndo == undo) bulkUndo = null;
                },
                done,
                error);
    }

    public void seriesOptions(
            Series s, boolean reverse, boolean hideWatched, Runnable done, Consumer<String> error) {
        mutation(
                () -> {
                    requireCurrent(s);
                    repository.seriesOptions(s.id, s.revision, reverse, hideWatched);
                },
                done,
                error);
    }

    public void rename(String id, String name, Runnable done, Consumer<String> error) {
        mutation(() -> repository.rename(id, name), done, error);
    }

    public void remove(String id, Runnable done, Consumer<String> error) {
        mutation(
                () -> {
                    refreshTickets.remove(id);
                    Future<?> job = refreshing.remove(id);
                    if (job != null) job.cancel(true);
                    quietQueue.remove(id);
                    repository.remove(id);
                    updateTrackedVideos();
                },
                done,
                error);
    }

    public void clearHistory(Runnable done, Consumer<String> error) {
        TrackerRuntime.clear();
        epoch = UUID.randomUUID().toString();
        String newEpoch = epoch;
        mutation(
                () -> {
                    repository.clearHistory(newEpoch);
                    updateTrackedVideos();
                    bulkUndo = null;
                },
                done,
                error);
    }

    private static String message(Exception failure) {
        String text = failure.getMessage();
        if (text != null && text.startsWith("series_tracker_error_")) return text;
        if (failure instanceof java.io.IOException)
            return "series_tracker_error_catalog_unavailable";
        return "series_tracker_error_operation";
    }
}
