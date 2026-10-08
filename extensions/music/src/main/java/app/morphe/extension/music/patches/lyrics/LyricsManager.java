/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2269
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics;

import android.media.MediaMetadata;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import app.morphe.extension.music.patches.album.PlayAlbumSongsPatch;
import app.morphe.extension.music.patches.album.PlaylistRequest;
import app.morphe.extension.music.patches.lyrics.model.Lyrics;
import app.morphe.extension.music.patches.lyrics.model.LyricsLine;
import app.morphe.extension.music.patches.lyrics.model.LyricsPreference;
import app.morphe.extension.music.patches.lyrics.model.MetadataCleaner;
import app.morphe.extension.music.patches.lyrics.model.TrackInfo;
import app.morphe.extension.music.patches.lyrics.model.Word;
import app.morphe.extension.music.patches.lyrics.parsers.CharactersConverter;
import app.morphe.extension.music.patches.lyrics.requests.AMLLProvider;
import app.morphe.extension.music.patches.lyrics.requests.AppleMusicProvider;
import app.morphe.extension.music.patches.lyrics.requests.BinimumProvider;
import app.morphe.extension.music.patches.lyrics.requests.BlyricsProvider;
import app.morphe.extension.music.patches.lyrics.requests.CaptionsFetcher;
import app.morphe.extension.music.patches.lyrics.requests.DeezerProvider;
import app.morphe.extension.music.patches.lyrics.requests.KuGouProvider;
import app.morphe.extension.music.patches.lyrics.requests.LRCLIBProvider;
import app.morphe.extension.music.patches.lyrics.requests.LocalLyricsFetcher;
import app.morphe.extension.music.patches.lyrics.requests.LunaBeatProvider;
import app.morphe.extension.music.patches.lyrics.requests.LunaProvider;
import app.morphe.extension.music.patches.lyrics.requests.LyricifyProvider;
import app.morphe.extension.music.patches.lyrics.requests.LyricsProvider;
import app.morphe.extension.music.patches.lyrics.requests.LyricsRequests;
import app.morphe.extension.music.patches.lyrics.requests.MusixmatchProvider;
import app.morphe.extension.music.patches.lyrics.requests.NetEaseProvider;
import app.morphe.extension.music.patches.lyrics.requests.PetitLyricsProvider;
import app.morphe.extension.music.patches.lyrics.requests.QQProvider;
import app.morphe.extension.music.patches.lyrics.requests.SimpMusicProvider;
import app.morphe.extension.music.patches.lyrics.requests.SpotifyProvider;
import app.morphe.extension.music.patches.lyrics.requests.UnisonProvider;
import app.morphe.extension.music.patches.lyrics.requests.YouTubeMusicProvider;
import app.morphe.extension.music.patches.lyrics.storage.LyricsCache;
import app.morphe.extension.music.patches.lyrics.ui.LyricsPanelInstaller;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.music.shared.VideoInformation;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * Fetches lyrics for the currently playing track and tracks playback position.
 *
 * <p>The position is extrapolated from the last {@link PlaybackState} update so synced
 * lyrics stay accurate to a few tens of milliseconds between updates. The player time hook
 * ({@link VideoInformation#getVideoTime()}, which ticks roughly once per second) only
 * re-anchors when it is ahead of that extrapolation, or when it is far behind and
 * {@link PlaybackState} itself has gone quiet — a slightly stale videoTime sample must never
 * pull playback backwards. A seek or play/pause also re-anchors via
 * {@link #onSetPlaybackState}.
 */
public final class LyricsManager {

    public enum State {
        IDLE,
        LOADING,
        LOADED,
        NOT_FOUND,
        ERROR
    }

    public interface Listener {

        /** Called on the main thread whenever the state or the lyrics change. */
        void onLyricsChanged(State state, @Nullable Lyrics lyrics);
    }

    private record ScoredCandidate(int score, int rank, Lyrics lyrics)
            implements Comparable<ScoredCandidate> {
        @Override
        public int compareTo(ScoredCandidate other) {
            if (score != other.score) return Integer.compare(other.score, score);
            if (rank != other.rank) return Integer.compare(other.rank, rank);
            return 0;
        }
    }

    private record PendingTrack(TrackInfo track, String rawTitle, String rawArtist,
                                @Nullable Uri mediaUri, String videoId) {
    }

    private static final LyricsManager INSTANCE = new LyricsManager();

    private static final long PENDING_COMMIT_MS = 1500;

    private final ExecutorService lookupExecutor = Executors.newFixedThreadPool(8);

    private final ExecutorService fetchExecutor = Executors.newFixedThreadPool(16);

    private final Set<Future<?>> activeFetches = ConcurrentHashMap.newKeySet();

    private <T> Future<T> submitFetch(int id, CompletionService<T> service,
                                      java.util.concurrent.Callable<T> fetch) {
        Future<T> future = service.submit(() -> id == requestId.get() ? fetch.call() : null);
        activeFetches.add(future);
        // A track can change between submission and registration.
        if (id != requestId.get()) {
            future.cancel(true);
            activeFetches.remove(future);
        }
        return future;
    }

    private void cancelPendingFetches() {
        for (Future<?> future : activeFetches) {
            future.cancel(true);
            activeFetches.remove(future);
        }
    }

    private void finishFetches(List<? extends Future<?>> futures) {
        for (Future<?> future : futures) {
            if (!future.isDone()) future.cancel(true);
            activeFetches.remove(future);
        }
    }

    private final List<Listener> listeners = new ArrayList<>(2);

    private static final int MAX_ARTIST_SONG_LINE_LENGTH = 80;

    private static final Pattern ARTIST_SONG_PATTERN =
            Pattern.compile("^[^\\-]+\\s*-\\s*[^\\-]+$");

    private static final int CREDIT_LINE_GAP_TOLERANCE = 3;

    @Nullable
    private static volatile String cachedCreditMarkers;
    @Nullable
    private static volatile List<String> cachedCreditVariants;

    @Nullable
    private volatile TrackInfo currentTrack;

    @NonNull
    private String currentVideoId = "";

    /** Metadata of the current track, kept so it can be read again as the song of an album. */
    @Nullable
    private MediaMetadata currentMetadata;

    /** Content/file URI of the currently displayed track, when played from local storage. */
    @Nullable
    private volatile Uri currentMediaUri;

    /** Raw (un-cleaned) title/artist of the current track, used for the MediaStore lookup. */
    @Nullable
    private String currentRawTitle;
    @Nullable
    private String currentRawArtist;

    @Nullable
    private PendingTrack pendingTrack;

    private int pendingCommitGeneration;

    @Nullable
    private volatile Lyrics currentLyrics;

    @Nullable
    private volatile List<SkipSegments.Seg> displaySegs;

    @Nullable
    private volatile String displaySegsVideoId;

    /**
     * The candidate segment the open submit dialog previews: playback time counts it
     * exactly like a stored segment until the dialog dismisses and
     * {@link #clearDialogSegment()} runs. Null while no dialog is up.
     */
    @Nullable
    private volatile SkipSegments.Seg dialogPreviewSeg;

    @Nullable
    private volatile String dialogPreviewVideoId;

    private volatile State state = State.IDLE;

    /**
     * Incremented for every track change so that a late response for a previous
     * track is discarded instead of being shown for the current one.
     */
    private final AtomicInteger requestId = new AtomicInteger();

    private long positionMs;
    private long positionUpdatedAtUptimeMs;
    private long lastVideoTimeSample = -1;
    private long lastPlaybackSampleUptimeMs;
    private float playbackSpeed = 1f;
    private boolean playing;

    private static final long VIDEO_REANCHOR_BEHIND_MS = 1000;
    private static final long PLAYBACK_STALE_MS = 500;

    private int lastHighlightedIndex = -1;

    private int temporaryOffsetMs = 0;

    private LyricsManager() {
        PlayAlbumSongsPatch.addSubstitutionListener(
                (videoId, resolvedVideoId) -> reloadCurrentTrack());
        VideoInformation.addVideoIdListener(videoId -> reloadCurrentTrack());
        LyricsPanelInstaller.registerSettingsListener();
        runOnFetchThread(LunaBeatProvider::preloadIndex);
        runOnFetchThread(() -> {
            MetadataCleaner.resolveSettingBlocking(Settings.LYRICS_CUSTOM_REGEX.get());
            MetadataCleaner.resolveSettingBlocking(Settings.LYRICS_TEXT_FILTER.get());
            MetadataCleaner.resolveSettingBlocking(Settings.LYRICS_CREDIT_LINE_REGEX.get());
        });
    }

    private void runOnLookupThread(Runnable body) {
        lookupExecutor.execute(() -> runContained(body));
    }

    private void runOnFetchThread(Runnable body) {
        fetchExecutor.execute(() -> runContained(body));
    }

    private static void runContained(Runnable body) {
        try {
            body.run();
        } catch (Throwable ignored) {
        }
    }

    private final PriorityQueue<ScoredCandidate> candidateQueue = new PriorityQueue<>();
    private final Set<String> shownFingerprints = ConcurrentHashMap.newKeySet();
    private volatile boolean phase2Done;

    /**
     * Set to the id of a lookup whose job is only to fill the queue, because the lyric
     * remembered for the track is already on screen. Cleared as soon as the user asks for
     * a different candidate.
     */
    private volatile int suppressForRequest = -1;

    /** Fingerprints of the candidates the user left in order for this track, in that order. */
    @Nullable
    private volatile List<String> rememberedQueue;

    /** Custom search terms remembered for this track, or null when the default terms were used. */
    @Nullable
    private volatile String searchQueryTitle;
    @Nullable
    private volatile String searchQueryArtist;

    /**
     * Fingerprint the remembered lyric had before it was written to disk. Writing drops the raw
     * text the fingerprint was taken from, so this is what tells a later lookup that the lyric
     * it just found is the one already on screen.
     */
    @Nullable
    private volatile String preferredFingerprint;

    /**
     * The video id the running lookup belongs to, captured on the thread that starts it:
     * {@link VideoInformation} is not volatile, so a background thread must not read it after
     * another video has started. It names the remembered preference of the playback.
     */
    @Nullable
    private volatile String preferenceVideoId;

    /**
     * The video id the running load belongs to, so {@link #ensureLoadedForCurrentVideo}
     * can tell that playback reached another video without one of the track events
     * starting a load for it: metadata can arrive empty or not at all on an auto-switch.
     */
    private String loadedForVideoId = "";

    private final Map<String, Lyrics> filteredCache =
            java.util.Collections.synchronizedMap(Utils.createSizeRestrictedMap(32));

    public static LyricsManager getInstance() {
        return INSTANCE;
    }

    public void addListener(Listener listener) {
        Utils.verifyOnMainThread();
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
        listener.onLyricsChanged(state, currentLyrics);
    }

    public void removeListener(Listener listener) {
        Utils.verifyOnMainThread();
        listeners.remove(listener);
    }

    @Nullable
    public TrackInfo getCurrentTrack() {
        return currentTrack;
    }

    @Nullable
    public String getCurrentRawTitle() {
        return currentRawTitle;
    }

    /** The track's untouched metadata artist; see {@link #getCurrentRawTitle()}. */
    @Nullable
    public String getCurrentRawArtist() {
        return currentRawArtist;
    }

    /** Whether lyrics for the current track are loaded and ready to show. */
    public boolean hasLyrics() {
        Lyrics current = currentLyrics;
        return state == State.LOADED && current != null && !current.isEmpty();
    }

    /** Whether the lyrics are usable (non-null, non-empty and not instrumental). */
    static boolean isValidLyrics(@Nullable Lyrics lyrics, @Nullable TrackInfo track) {
        if (lyrics == null || lyrics == Lyrics.NOT_FOUND || lyrics.isEmpty()) {
            return false;
        }
        Lyrics filtered = filterCreditLines(lyrics, track);
        filtered = filterLyricsText(filtered);
        return !filtered.isEmpty();
    }

    private void resetPosition() {
        positionMs = 0;
        positionUpdatedAtUptimeMs = SystemClock.uptimeMillis();
        lastVideoTimeSample = -1;
        lastPlaybackSampleUptimeMs = 0;
        lastHighlightedIndex = -1;
    }

    /**
     * Current playback position on the released-audio timeline: while the segment switch
     * is on, time the sponsor block segments have already passed is removed first, then
     * the user configured offset and the temporary offset are applied.
     */
    public long getPositionMs() {
        return SkipSegments.contentMs(computeExpectedPositionMs(), syncSegs())
                - Settings.LYRICS_OFFSET_MS.get() - temporaryOffsetMs;
    }

    public long getVideoPositionMs() {
        return computeExpectedPositionMs();
    }

    private long computeExpectedPositionMs() {
        final long now = SystemClock.uptimeMillis();
        long expected = positionMs;
        if (playing && positionUpdatedAtUptimeMs != 0) {
            expected += (long) ((now - positionUpdatedAtUptimeMs) * playbackSpeed);
        }

        final long videoTime = VideoInformation.getVideoTime();
        if (videoTime > 0 && videoTime != lastVideoTimeSample) {
            lastVideoTimeSample = videoTime;
            if (videoTime > expected) {
                positionMs = videoTime;
                positionUpdatedAtUptimeMs = now;
            } else if (expected - videoTime > VIDEO_REANCHOR_BEHIND_MS
                    && now - lastPlaybackSampleUptimeMs > PLAYBACK_STALE_MS) {
                positionMs = videoTime;
                positionUpdatedAtUptimeMs = now;
            }
            expected = positionMs;
            if (playing && positionUpdatedAtUptimeMs != 0) {
                expected += (long) ((now - positionUpdatedAtUptimeMs) * playbackSpeed);
            }
        }
        return expected;
    }

    public int getTemporaryOffsetMs() { return temporaryOffsetMs; }

    public void setTemporaryOffsetMs(int ms) { temporaryOffsetMs = ms; }

    public void resetTemporaryOffsetMs() { temporaryOffsetMs = 0; }

    /**
     * Injection point relay. Called on the main thread.
     */
    public void onSetMetadata(@Nullable MediaMetadata metadata) {
        Utils.verifyOnMainThread();
        if (metadata == null) {
            return;
        }
        resetTemporaryOffsetMs();
        currentMetadata = metadata;
        loadTrackOf(metadata);
        ensureLoadedForCurrentVideo();
    }

    /**
     * The song of an album, and the video id of the app itself, can both land after the metadata
     * of a track, so the track is read again whenever either of them arrives.
     */
    public void reloadCurrentTrack() {
        Utils.runOnMainThread(() -> {
            MediaMetadata metadata = currentMetadata;
            if (metadata != null) {
                loadTrackOf(metadata);
            }
            ensureLoadedForCurrentVideo();
        });
    }

    public void reloadAfterSettingsChange() {
        loadedForVideoId = "";
        reloadCurrentTrack();
    }

    private void loadTrackOf(MediaMetadata metadata) {
        if (!Settings.LYRICS_ENABLED.get()) {
            return;
        }

        String rawTitle = metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
        String rawArtist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST);
        if (rawTitle == null || rawTitle.trim().isEmpty() || rawArtist == null || rawArtist.trim().isEmpty()) {
            return;
        }

        int durationSeconds = (int) (metadata.getLong(MediaMetadata.METADATA_KEY_DURATION) / 1000);

        // An album track playing its song version is still described by the app as the music
        // video, whose title and duration find no lyrics or the lyrics of another version.
        PlaylistRequest.Song song = PlayAlbumSongsPatch.getSong(VideoInformation.getVideoId());
        if (song != null) {
            rawTitle = song.title();
            if (song.durationSeconds() > 0) {
                durationSeconds = song.durationSeconds();
            }
        }

        String[] parsed = MetadataCleaner.parseCleanTitleAndArtist(rawTitle, rawArtist);
        String effectiveTitle = parsed[1];
        String effectiveArtist = parsed[0];

        TrackInfo track = new TrackInfo(
                effectiveTitle,
                effectiveArtist,
                MetadataCleaner.cleanAlbum(metadata.getString(MediaMetadata.METADATA_KEY_ALBUM)),
                durationSeconds
        );

        if (track.title() == null || track.title().isEmpty() || track.artist() == null || track.artist().isEmpty()) {
            return;
        }

        Uri mediaUri = parseMediaUri(metadata);
        String videoId = VideoInformation.getVideoId();
        if (Settings.LYRICS_SB_MATCHING.get()
                && !isLocalUri(mediaUri) && durationSeconds > 0) {
            SkipSegments.prefetch(song == null ? videoId : song.videoId());
        }
        TrackInfo existingTrack = currentTrack;
        if (track.equals(existingTrack)) {
            cancelPendingTrack();
            if (videoId.equals(currentVideoId)) {
                currentRawTitle = rawTitle;
                currentRawArtist = rawArtist;
                currentMediaUri = mediaUri;
                if (track.durationSeconds() > 0
                        && track.durationSeconds() != existingTrack.durationSeconds()) {
                    currentTrack = track;
                }
                return;
            }
            commitTrackChange(track, rawTitle, rawArtist, mediaUri, videoId, true);
            return;
        }
        if (existingTrack == null || durationSeconds > 0 || !videoId.equals(currentVideoId)) {
            cancelPendingTrack();
            commitTrackChange(track, rawTitle, rawArtist, mediaUri, videoId, false);
            return;
        }
        setPendingTrack(track, rawTitle, rawArtist, mediaUri, videoId);
    }

    /**
     * Starts the load a track event failed to start: on an automatic switch the metadata
     * can arrive empty or not at all, so nothing loads the video that is actually playing
     * until the refresh button is pressed. The video id the last load started for is the
     * marker, which makes this idempotent across the events that do arrive.
     */
    private void ensureLoadedForCurrentVideo() {
        if (!Settings.LYRICS_ENABLED.get() || currentTrack == null || pendingTrack != null) {
            return;
        }
        final String videoId = VideoInformation.getVideoId();
        if (videoId.isEmpty() || videoId.equals(loadedForVideoId)) {
            return;
        }
        currentVideoId = videoId;
        load(currentTrack, true);
    }

    private void commitTrackChange(TrackInfo track, String rawTitle, String rawArtist,
                                   @Nullable Uri mediaUri, String videoId, boolean sameTrack) {
        currentRawTitle = rawTitle;
        currentRawArtist = rawArtist;
        currentMediaUri = mediaUri;
        currentTrack = track;
        currentVideoId = videoId;
        resetPosition();
        load(track, sameTrack);
    }

    private void setPendingTrack(TrackInfo track, String rawTitle, String rawArtist,
                                 @Nullable Uri mediaUri, String videoId) {
        if (pendingTrack != null && pendingTrack.track().equals(track)) {
            return;
        }
        pendingCommitGeneration++;
        pendingTrack = new PendingTrack(track, rawTitle, rawArtist, mediaUri, videoId);
        final int generation = pendingCommitGeneration;
        Utils.runOnMainThreadDelayed(() -> {
            if (generation != pendingCommitGeneration || pendingTrack == null) {
                return;
            }
            commitPendingTrack();
        }, PENDING_COMMIT_MS);
    }

    private void cancelPendingTrack() {
        if (pendingTrack == null) {
            return;
        }
        pendingCommitGeneration++;
        pendingTrack = null;
    }

    private void commitPendingTrack() {
        PendingTrack pending = pendingTrack;
        if (pending == null) {
            return;
        }
        pendingTrack = null;
        pendingCommitGeneration++;
        commitTrackChange(pending.track(), pending.rawTitle(), pending.rawArtist(),
                pending.mediaUri(), pending.videoId(), false);
    }

    /**
     * Injection point relay. Called on the main thread.
     */
    public void onSetPlaybackState(@Nullable PlaybackState playbackState) {
        Utils.verifyOnMainThread();
        if (playbackState == null) {
            return;
        }

        playing = playbackState.getState() == PlaybackState.STATE_PLAYING;
        positionMs = playbackState.getPosition();
        positionUpdatedAtUptimeMs = SystemClock.uptimeMillis();
        lastPlaybackSampleUptimeMs = positionUpdatedAtUptimeMs;

        final float speed = playbackState.getPlaybackSpeed();
        // A paused state reports a speed of zero, which would freeze extrapolation
        // even after playback resumes, so only positive speeds are kept.
        if (speed > 0) {
            playbackSpeed = speed;
        }
        ensureLoadedForCurrentVideo();
    }

    public void onDisplayedTrackChanged(@Nullable String title, @Nullable String artist, @Nullable Uri mediaUri) {
        Utils.verifyOnMainThread();
        if (title == null || title.trim().isEmpty() || artist == null || artist.trim().isEmpty()) {
            return;
        }
        PlaylistRequest.Song song = PlayAlbumSongsPatch.getSong(VideoInformation.getVideoId());
        if (song != null && song.title() != null) {
            title = song.title();
        }

        String[] parsed = MetadataCleaner.parseCleanTitleAndArtist(title, artist);
        String cleanedTitle = parsed[1];
        String cleanedArtist = parsed[0];
        if (cleanedTitle == null || cleanedTitle.isEmpty() || cleanedArtist == null || cleanedArtist.isEmpty()) {
            return;
        }

        TrackInfo current = currentTrack;
        if (current != null
                && Objects.equals(current.title(), cleanedTitle)
                && Objects.equals(current.artist(), cleanedArtist)) {
            currentRawTitle = title;
            currentRawArtist = artist;
            if (mediaUri != null) {
                currentMediaUri = mediaUri;
            }
            return;
        }

        final TrackInfo previous = currentTrack;
        TrackInfo track = new TrackInfo(cleanedTitle, cleanedArtist,
                previous == null ? "" : previous.album(),
                previous == null ? 0 : previous.durationSeconds());
        if (previous == null) {
            commitTrackChange(track, title, artist, mediaUri,
                    VideoInformation.getVideoId(), false);
            return;
        }
        if (pendingTrack != null && track.equals(pendingTrack.track())) {
            return;
        }
        setPendingTrack(track, title, artist, mediaUri, VideoInformation.getVideoId());
    }

    /**
     * Returns true for tracks backed by a local file ({@code file://} or {@code content://}) as
     * opposed to a streamed YouTube video ({@code http(s)://}). Only local files can carry
     * embedded lyrics in their tags.
     */
    private static boolean isLocalUri(@Nullable Uri uri) {
        return uri != null && ("file".equals(uri.getScheme()) || "content".equals(uri.getScheme()));
    }

    @Nullable
    public static Uri parseMediaUri(@NonNull MediaMetadata metadata) {
        String uri = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_URI);
        return (uri != null) ? Uri.parse(uri) : null;
    }

    private void load(TrackInfo track, boolean keepLyrics) {
        final int id = requestId.incrementAndGet();
        cancelPendingFetches();
        Lyrics current = currentLyrics;
        setState(State.LOADING, keepLyrics ? current : null);

        synchronized (candidateQueue) {
            candidateQueue.clear();
            phase2Done = false;
        }
        shownFingerprints.clear();
        filteredCache.clear();
        lastHighlightedIndex = -1;
        suppressForRequest = -1;
        preferredFingerprint = null;
        rememberedQueue = null;
        searchQueryTitle = null;
        searchQueryArtist = null;
        LyricsRequests.setCustomMatchMode(false);
        preferenceVideoId = VideoInformation.getVideoId();
        loadedForVideoId = preferenceVideoId;
        displaySegs = null;

        runOnLookupThread(() -> runProviderLookup(id, track));
    }

    /**
     * Fetches the next candidate lyrics. Called from the refresh button.
     * Never replaces the current lyrics until a new valid candidate is chosen;
     * the queue is seeded from the 2nd item onward (the current best is skipped).
     */
    public void fetchNextCandidate() {
        Utils.verifyOnMainThread();
        TrackInfo track = currentTrack;
        if (track == null) {
            return;
        }

        final int id = requestId.incrementAndGet();
        cancelPendingFetches();

        suppressForRequest = -1;

        Lyrics current = currentLyrics;
        setState(State.LOADING, current);

        runOnLookupThread(() -> {
            final String sbVideoId = skipSegmentsVideoId();
            final List<SkipSegments.Seg> segs = SkipSegments.await(sbVideoId,
                    track.durationSeconds() * 1000L);
            if (id == requestId.get()) {
                displaySegsVideoId = sbVideoId;
                displaySegs = segs;
            }
            final TrackInfo searchTrack = withEffectiveDuration(track, segs);
            phase2Done = false;
            if (pollAndPublishNext(id, track)) {
                return;
            }

            if (!phase2Done) {
                String order = Settings.LYRICS_SOURCE.get();
                List<LyricsProvider> providers = providersInOrder(order);
                // The queue keeps coming from the terms the user searched, so the candidates
                // have to be fetched with the same terms and by the same providers.
                TrackInfo queryTrack = customQueryTrack(searchTrack);
                if (queryTrack != null) {
                    providers = searchableProviders(providers);
                }
                collectRemainingCandidates(id, queryTrack != null ? queryTrack : searchTrack, providers);

                if (pollAndPublishNext(id, track)) {
                    return;
                }
                if (id == requestId.get()) {
                    phase2Done = true;
                }
            }

            Utils.runOnMainThread(() -> {
                if (id != requestId.get()) {
                    return;
                }
                Lyrics currentFinal = currentLyrics;
                if (currentFinal != null && !currentFinal.isEmpty()) {
                    setState(State.LOADED, currentFinal);
                } else {
                    setState(State.NOT_FOUND, null);
                }
            });
        });
    }

    private boolean pollAndPublishNext(int id, TrackInfo track) {
        while (true) {
            ScoredCandidate next;
            synchronized (candidateQueue) {
                next = candidateQueue.poll();
            }
            if (next == null) {
                return false;
            }
            if (id != requestId.get()) {
                return false;
            }
            if (next.lyrics() != currentLyrics
                    && !shownFingerprints.contains(fingerprint(next.lyrics()))
                    && isValidLyrics(next.lyrics(), track)) {
                publishFromLookup(id, next.lyrics());
                return true;
            }
        }
    }

    private void collectRemainingCandidates(int id, TrackInfo track,
                                            List<LyricsProvider> providers) {
        if (id != requestId.get()) {
            return;
        }
        final TrackInfo candidateTrack = LyricsRequests.isCustomMatchMode()
                ? new TrackInfo(track.title(), track.artist(), "", 0) : track;
        Set<String> existing = new HashSet<>(shownFingerprints);
        synchronized (candidateQueue) {
            for (ScoredCandidate sc : candidateQueue) {
                existing.add(fingerprint(sc.lyrics()));
            }
        }
        Lyrics current = currentLyrics;
        if (current != null) {
            existing.add(fingerprint(current));
        }

        CompletionService<List<Lyrics.ScoredLyrics>> cs =
                new ExecutorCompletionService<>(fetchExecutor);
        List<Future<List<Lyrics.ScoredLyrics>>> futures = new ArrayList<>();
        for (LyricsProvider provider : providers) {
            if (!provider.hasCandidates()) {
                continue;
            }
            futures.add(submitFetch(id, cs, () -> {
                try {
                    return provider.fetchCandidates(candidateTrack);
                } catch (Exception ex) {
                    Logger.printDebug(() -> "Phase 2 fetch failed: " + provider.name(), ex);
                    return null;
                }
            }));
        }

        int completed = 0;
        while (completed < futures.size()) {
            if (id != requestId.get()) {
                break;
            }
            Future<List<Lyrics.ScoredLyrics>> f;
            try {
                f = cs.poll(5, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Logger.printDebug(() -> "Interrupted polling candidate futures", ex);
                Thread.currentThread().interrupt();
                break;
            }
            if (f == null) {
                Future<List<Lyrics.ScoredLyrics>> extra;
                while ((extra = cs.poll()) != null) {
                    completed++;
                    processCandidateFuture(extra, id, candidateTrack, existing);
                }
                break;
            }
            completed++;
            processCandidateFuture(f, id, candidateTrack, existing);
        }

        finishFetches(futures);
    }

    private void processCandidateFuture(Future<List<Lyrics.ScoredLyrics>> f, int id,
                                        TrackInfo track, Set<String> existing) {
        if (id != requestId.get()) {
            return;
        }
        try {
            List<Lyrics.ScoredLyrics> candidates = f.get();
            if (candidates == null) {
                return;
            }
            for (Lyrics.ScoredLyrics sc : candidates) {
                Lyrics candidate = sc.lyrics();
                if (!isValidLyrics(candidate, track)) {
                    continue;
                }
                String fp = fingerprint(candidate);
                synchronized (candidateQueue) {
                    if (id == requestId.get() && existing.add(fp)) {
                        int sync = LyricsRequests.syncRank(candidate);
                        int match = sc.score() - sync;
                        int composite = LyricsRequests.composite(match, candidate, 0);
                        candidateQueue.add(new ScoredCandidate(composite, sync, candidate));
                    }
                }
            }
        } catch (Exception ex) {
            Logger.printDebug(() -> "Failed to process candidate result", ex);
        }
    }

    private static String fingerprint(Lyrics lyrics) {
        if (lyrics == null) return "";
        String provider = lyrics.providerName();
        String raw = lyrics.rawFormat();
        if (raw != null && !raw.isEmpty()) {
            return provider + "|" + raw.hashCode();
        }
        StringBuilder sb = new StringBuilder();
        for (LyricsLine line : lyrics.lines()) {
            sb.append(line.text());
        }
        return provider + "|" + sb.toString().hashCode();
    }

    /**
     * Shows the lyric the user landed on for this track and arms the queue to be filled
     * around it instead of replacing it. Runs on a lookup thread, and reports whether the
     * lookup that called it has to treat everything it finds as queue material.
     */
    private boolean applyRememberedPreference(int id, TrackInfo track) {
        final LyricsPreference preference = LyricsCache.getPreference(preferenceVideoId, track);
        if (preference == null || preference.preferred() == null) {
            return false;
        }
        final Lyrics raw = preference.preferred();
        final List<String> queue = preference.queue();
        final String queryTitle = preference.queryTitle();
        final String queryArtist = preference.queryArtist();
        final String storedFingerprint = preference.fingerprint();
        if (!isValidLyrics(raw, track)) {
            Logger.printInfo(() -> "LyricsPref skipped: filters keep out the remembered lyric");
            return false;
        }
        if (id != requestId.get()) {
            return false;
        }
        // Everything the lookup ahead of this call reads is armed here, on the thread that
        // reads it: it checks the suppression right after this call returns. The main runnable
        // below is queued before anything that lookup publishes, so publish still sees it.
        rememberedQueue = queue.isEmpty() ? null : queue;
        searchQueryTitle = queryTitle;
        searchQueryArtist = queryArtist;
        preferredFingerprint = storedFingerprint;
        suppressForRequest = id;
        if (id != requestId.get()) {
            rememberPreferenceDisarmed();
            return false;
        }
        Lyrics preferred;
        try {
            preferred = prepareForDisplay(raw);
        } catch (Throwable ignored) {
            rememberPreferenceDisarmed();
            return false;
        }
        final Lyrics display = preferred;
        Utils.runOnMainThread(() -> {
            if (id != requestId.get()) {
                return;
            }
            if (display == null || display == Lyrics.NOT_FOUND || display.isEmpty()) {
                // Nothing of it survives the current filters. The lookup ahead was already
                // told to hold back, so the suppression is released before it can return
                // without showing anything.
                rememberPreferenceDisarmed();
                setState(State.NOT_FOUND, null);
                return;
            }
            setState(State.LOADED, display);
            shownFingerprints.add(fingerprint(display));
            if (storedFingerprint != null) {
                shownFingerprints.add(storedFingerprint);
            }
            Logger.printInfo(() -> "LyricsPref applied: lines=" + (display.lines() != null ? display.lines().size() : 0));
        });
        return true;
    }

    private void rememberPreferenceDisarmed() {
        suppressForRequest = -1;
        rememberedQueue = null;
        searchQueryTitle = null;
        searchQueryArtist = null;
        preferredFingerprint = null;
    }

    /**
     * The terms a lookup has to ask about when the user searched them explicitly, or null when
     * the metadata of the playing track is the right thing to ask.
     */
    @Nullable
    private TrackInfo customQueryTrack(TrackInfo track) {
        String title = searchQueryTitle;
        String artist = searchQueryArtist;
        if (title == null || title.isEmpty() || artist == null || artist.isEmpty()) {
            return null;
        }
        return new TrackInfo(title, artist, track.album(), track.durationSeconds());
    }

    /**
     * Puts the queue back in the order the user left it: candidates they had not reached yet
     * keep their remembered place, and anything new arrives after them in score order.
     */
    private void reorderQueueByRemembered() {
        List<String> remembered = rememberedQueue;
        if (remembered == null || remembered.isEmpty()) {
            return;
        }
        synchronized (candidateQueue) {
            if (candidateQueue.isEmpty()) {
                return;
            }
            List<ScoredCandidate> ordered = new ArrayList<>(candidateQueue);
            candidateQueue.clear();
            ordered.sort((a, b) -> {
                int indexA = remembered.indexOf(fingerprint(a.lyrics()));
                int indexB = remembered.indexOf(fingerprint(b.lyrics()));
                if (indexA != indexB) {
                    // Anything never remembered sorts after everything that was.
                    if (indexA < 0) indexA = Integer.MAX_VALUE;
                    if (indexB < 0) indexB = Integer.MAX_VALUE;
                    return Integer.compare(indexA, indexB);
                }
                return a.compareTo(b);
            });
            candidateQueue.addAll(ordered);
        }
    }

    private List<String> orderedQueueFingerprints() {
        List<ScoredCandidate> ordered = new ArrayList<>();
        synchronized (candidateQueue) {
            while (!candidateQueue.isEmpty()) {
                ordered.add(candidateQueue.poll());
            }
            candidateQueue.addAll(ordered);
        }
        List<String> fingerprints = new ArrayList<>(ordered.size());
        for (ScoredCandidate candidate : ordered) {
            fingerprints.add(fingerprint(candidate.lyrics()));
        }
        return fingerprints;
    }

    private void rememberPreference(Lyrics shown) {
        final TrackInfo track = currentTrack;
        if (track == null || shown == null || shown == Lyrics.NOT_FOUND || shown.isEmpty()) {
            return;
        }
        final String videoId = preferenceVideoId;
        final String queryTitle = searchQueryTitle;
        final String queryArtist = searchQueryArtist;
        final String fingerprint = fingerprint(shown);
        final List<String> queue = orderedQueueFingerprints();
        preferredFingerprint = fingerprint;
        runOnLookupThread(() -> LyricsCache.putPreference(videoId, track,
                queryTitle, queryArtist, shown, queue, fingerprint));
    }

    private void resnapshotPreference(int id) {
        Utils.runOnMainThread(() -> {
            if (id != requestId.get()) {
                return;
            }
            rememberPreference(currentLyrics);
        });
    }

    /**
     * Collects a lyric found while the remembered one is on screen: it belongs to the queue,
     * not to the screen. A result that only differs from what is shown by the lines that get
     * filtered out is the same lyric and is dropped.
     */
    private void enqueueWhileSuppressed(Lyrics lyrics, Lyrics prepared) {
        String incoming = fingerprint(lyrics);
        if (shownFingerprints.contains(incoming)) {
            return;
        }
        if (prepared == null || prepared == Lyrics.NOT_FOUND || prepared.isEmpty()) {
            return;
        }
        String preparedFp = fingerprint(prepared);
        if (shownFingerprints.contains(preparedFp)) {
            return;
        }
        Lyrics shown = currentLyrics;
        if (shown != null && preparedFp.equals(fingerprint(shown))) {
            return;
        }
        synchronized (candidateQueue) {
            if (shownFingerprints.contains(incoming) || shownFingerprints.contains(preparedFp)) {
                return;
            }
            for (ScoredCandidate candidate : candidateQueue) {
                if (fingerprint(candidate.lyrics()).equals(incoming)) {
                    return;
                }
            }
            int sync = LyricsRequests.syncRank(lyrics);
            candidateQueue.add(new ScoredCandidate(
                    LyricsRequests.composite(LyricsRequests.NEUTRAL, lyrics, 0), sync, lyrics));
        }
    }

    /**
     * The providers that can answer a custom title and artist: the ones keyed by the video id
     * of the playing track only know that track.
     */
    private static List<LyricsProvider> searchableProviders(List<LyricsProvider> providers) {
        List<LyricsProvider> result = new ArrayList<>(providers.size());
        for (LyricsProvider provider : providers) {
            switch (provider.name()) {
                case "YTMusic", "Captions", "Unison", "SimpMusic" -> { }
                default -> result.add(provider);
            }
        }
        return result;
    }

    public boolean hasSearchProviders() {
        return !searchableProviders(providersInOrder(Settings.LYRICS_SOURCE.get())).isEmpty();
    }

    /** The custom search terms remembered for this track, or null when defaults were used. */
    @Nullable
    public String[] rememberedSearchTerms() {
        String title = searchQueryTitle;
        if (title == null) {
            return null;
        }
        String artist = searchQueryArtist;
        return new String[]{title, artist != null ? artist : ""};
    }

    /**
     * Searches with the terms the user typed and replaces the candidate queue with what those
     * terms find. Called from the search dialog on the main thread.
     *
     * @param defaultTerms true when the terms are still the track's own metadata, which then
     *                     go through the usual metadata cleaning; typed terms do not.
     */
    public void searchWithCustomQuery(String title, String artist, boolean defaultTerms) {
        Utils.verifyOnMainThread();
        final TrackInfo track = currentTrack;
        if (track == null) {
            return;
        }
        cancelPendingTrack();
        final String queryTitle = title != null ? title.trim() : "";
        final String queryArtist = artist != null ? artist.trim() : "";
        if (queryTitle.isEmpty() || queryArtist.isEmpty()) {
            return;
        }

        final int id = requestId.incrementAndGet();
        cancelPendingFetches();
        preferenceVideoId = VideoInformation.getVideoId();
        Lyrics current = currentLyrics;
        setState(State.LOADING, current);
        suppressForRequest = -1;
        rememberedQueue = null;
        LyricsRequests.setCustomMatchMode(true);
        if (defaultTerms) {
            searchQueryTitle = null;
            searchQueryArtist = null;
        } else {
            searchQueryTitle = queryTitle;
            searchQueryArtist = queryArtist;
        }

        // The video id providers only know the track that is playing, so they can only be
        // asked when the search terms are the metadata of that track.
        final List<LyricsProvider> enabledProviders =
                providersInOrder(Settings.LYRICS_SOURCE.get());
        final List<LyricsProvider> providers = defaultTerms
                ? enabledProviders
                : searchableProviders(enabledProviders);
        if (providers.isEmpty()) {
            publishFromLookup(id, Lyrics.NOT_FOUND);
            return;
        }

        runOnLookupThread(() -> {
            final String sbVideoId = skipSegmentsVideoId();
            final List<SkipSegments.Seg> segs = SkipSegments.await(sbVideoId,
                    track.durationSeconds() * 1000L);
            if (id == requestId.get()) {
                displaySegsVideoId = sbVideoId;
                displaySegs = segs;
            }
            final TrackInfo searchTrack = withEffectiveDuration(track, segs);
            final TrackInfo queryTrack = defaultTerms
                    ? searchTrack
                    : new TrackInfo(queryTitle, queryArtist, searchTrack.album(),
                    searchTrack.durationSeconds());
            synchronized (candidateQueue) {
                candidateQueue.clear();
                phase2Done = false;
            }
            shownFingerprints.clear();
            // The lyric on screen stays until the search answers.
            if (current != null) {
                shownFingerprints.add(fingerprint(current));
            }
            String preferredFp = preferredFingerprint;
            if (preferredFp != null) {
                shownFingerprints.add(preferredFp);
            }

            Tiers tiers = buildTiers(queryTrack, defaultTerms ? currentRawTitle : queryTitle);
            boolean[] failed = {false};
            LookupResult fetchResult = fetchFromProviders(id, tiers.original(), tiers.derived(),
                    failed, providers, !defaultTerms);
            Lyrics result = fetchResult.best;
            boolean validResult = isValidLyrics(result, track);

            seedCandidateQueue(id, fetchResult.scored, validResult, result);
            resnapshotPreference(id);

            if (id != requestId.get()) {
                return;
            }
            if (validResult) {
                if (!LyricsRequests.isCustomMatchMode()) {
                    LyricsCache.put(track, result.providerName(), result);
                }
                publishFromLookup(id, result);
            } else {
                publishFromLookup(id, Lyrics.NOT_FOUND);
            }

            scheduleQueueFill(id, fetchResult.queueFillPending, tiers.original(),
                    tiers.derived(), providers);
        });
    }

    private void runProviderLookup(int id, TrackInfo trackParam) {
        if (id != requestId.get()) return;
        final String sbVideoId = skipSegmentsVideoId();
        final List<SkipSegments.Seg> segs = SkipSegments.await(sbVideoId,
                trackParam.durationSeconds() * 1000L);
        if (id == requestId.get()) {
            displaySegsVideoId = sbVideoId;
            displaySegs = segs;
        }
        final TrackInfo track = withEffectiveDuration(trackParam, segs);
        final boolean suppressed = applyRememberedPreference(id, track);

        // Local files take priority: read embedded LYRICS/LYRIC tags before hitting providers.
        if (Settings.LYRICS_USE_EMBEDDED.get()) {
            Uri embeddedUri = localUriFor(track);
            if (embeddedUri != null) {
                Lyrics embedded = LocalLyricsFetcher.fetch(embeddedUri);
                if (embedded != null) {
                    LyricsCache.put(track, "LOCAL", embedded);
                    publishFromLookup(id, embedded);
                    if (!suppressed) {
                        return;
                    }
                }
            }
        }

        String order = Settings.LYRICS_SOURCE.get();
        List<LyricsProvider> providers = providersInOrder(order);
        final TrackInfo queryTrack = customQueryTrack(track);
        if (queryTrack != null) {
            // The queue is being rebuilt from custom terms, which the providers keyed by
            // video id cannot answer. The cache is keyed by the playing track and holds what
            // the default terms found, so its misses say nothing about these terms.
            providers = searchableProviders(providers);
        }
        if (providers.isEmpty()) {
            publishFromLookup(id, Lyrics.NOT_FOUND);
            return;
        }

        int notFoundCached = 0;
        for (LyricsProvider provider : providers) {
            Lyrics cached = LyricsCache.get(track, provider.name());
            if (cached != null && cached != Lyrics.NOT_FOUND) {
                publishFromLookup(id, cached);
                if (!suppressed) {
                    return;
                }
                continue;
            }
            boolean providerMissed = cached == Lyrics.NOT_FOUND && queryTrack == null;
            if (providerMissed) {
                notFoundCached++;
            }
        }
        if (queryTrack == null && notFoundCached >= providers.size()) {
            publishFromLookup(id, Lyrics.NOT_FOUND);
            return;
        }

        if (!Utils.isNetworkConnected()) {
            setErrorStateIfCurrent(id);
            return;
        }

        boolean[] failed = {false};
        Lyrics result;

        // Custom terms carry no InnerTube metadata of the playing track, which says nothing
        // about them.
        Tiers tiers = queryTrack != null
                ? buildTiers(queryTrack, queryTrack.title())
                : buildTiers(track, currentRawTitle);

        LookupResult fetchResult = fetchFromProviders(id, tiers.original(), tiers.derived(),
                failed, providers, false);
        if (id != requestId.get()) return;
        result = fetchResult.best;

        boolean validResult = isValidLyrics(result, track);
        if (validResult) {
            if (!LyricsRequests.isCustomMatchMode()) {
                LyricsCache.put(track, result.providerName(), result);
            }
            publishFromLookup(id, result);
        }

        seedCandidateQueue(id, fetchResult.scored, validResult, result);
        resnapshotPreference(id);

        scheduleQueueFill(id, fetchResult.queueFillPending, tiers.original(),
                tiers.derived(), providers);

        if (validResult) {
            return;
        }

        // A miss on the track's own terms marks the provider as having nothing for the track;
        // a miss on typed terms says nothing about them and leaves the cache untouched.
        if (queryTrack == null) {
            for (LyricsProvider provider : providers) {
                if (fetchResult.providersWithResults.contains(provider.name())) {
                    continue;
                }
                if (fetchResult.providersFailed.contains(provider.name())) {
                    continue;
                }
                if (!fetchResult.providersAttempted.contains(provider.name())) {
                    continue;
                }
                LyricsCache.put(track, provider.name(), Lyrics.NOT_FOUND);
            }
        }
        if (failed[0]) {
            setErrorStateIfCurrent(id);
        } else {
            publishFromLookup(id, Lyrics.NOT_FOUND);
        }
    }

    private void fillQueueInBackground(int id, List<VariantQuery> originalTier,
                                       List<VariantQuery> derivedTier,
                                       List<LyricsProvider> providers) {
        if (id != requestId.get()) {
            return;
        }
        List<LyricsProvider> nonBlind = new ArrayList<>();
        List<LyricsProvider> blind = new ArrayList<>();
        for (LyricsProvider p : providers) {
            if (isBlindProvider(p)) {
                blind.add(p);
            } else {
                nonBlind.add(p);
            }
        }
        final int stage1Count = Math.min(4, nonBlind.size());
        List<LyricsProvider> stage2 = nonBlind.subList(stage1Count, nonBlind.size());

        List<VariantQuery> work = new ArrayList<>(originalTier);
        work.addAll(derivedTier);

        CompletionService<ProviderFetch> cs = new ExecutorCompletionService<>(fetchExecutor);
        List<Future<ProviderFetch>> futures = new ArrayList<>();
        Set<String> attempted = ConcurrentHashMap.newKeySet();
        Set<String> withResults = ConcurrentHashMap.newKeySet();
        Set<String> failedSet = ConcurrentHashMap.newKeySet();
        AtomicBoolean threadFailed = new AtomicBoolean(false);
        Set<String> seenPairs = ConcurrentHashMap.newKeySet();

        for (VariantQuery vq : work) {
            final boolean isOriginal = originalTier.contains(vq);
            List<LyricsProvider> eligible = new ArrayList<>();
            if (isOriginal) {
                eligible.addAll(stage2);
                eligible.addAll(blind);
            } else {
                eligible.addAll(nonBlind);
                eligible.addAll(blind);
            }
            for (LyricsProvider provider : eligible) {
                String pair = provider.name() + '|' + System.identityHashCode(vq.track())
                        + '|' + vq.penalty();
                if (!seenPairs.add(pair)) {
                    continue;
                }
                futures.add(submitFetch(id, cs, () -> fetchOne(provider, vq, attempted, withResults,
                        failedSet, threadFailed)));
            }
        }

        long deadline = SystemClock.uptimeMillis() + 5_000;
        List<ScoredCandidate> collected = new ArrayList<>();
        DisplayState fillDisplay = new DisplayState(false, false);
        int completed = 0;
        while (completed < futures.size()) {
            if (id != requestId.get()) {
                break;
            }
            long remaining = deadline - SystemClock.uptimeMillis();
            if (remaining <= 0) {
                break;
            }
            Future<ProviderFetch> f;
            try {
                f = cs.poll(remaining, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            }
            if (f == null) {
                break;
            }
            completed++;
            try {
                fillDisplay.accept(f.get(), collected, false);
            } catch (Exception ex) {
                Logger.printDebug(() -> "Queue fill fetch failed", ex);
            }
        }
        Future<ProviderFetch> extra;
        while ((extra = cs.poll()) != null) {
            try {
                fillDisplay.accept(extra.get(), collected, false);
            } catch (Exception ignored) {
            }
        }
        finishFetches(futures);

        if (collected.isEmpty() || id != requestId.get()) {
            return;
        }
        Set<String> existing = new HashSet<>(shownFingerprints);
        synchronized (candidateQueue) {
            if (id != requestId.get()) {
                return;
            }
            for (ScoredCandidate sc : candidateQueue) {
                existing.add(fingerprint(sc.lyrics()));
            }
            Lyrics current = currentLyrics;
            if (current != null) {
                existing.add(fingerprint(current));
            }
            for (ScoredCandidate sc : collected) {
                if (id == requestId.get() && existing.add(fingerprint(sc.lyrics()))) {
                    candidateQueue.add(sc);
                }
            }
        }
        reorderQueueByRemembered();
        resnapshotPreference(id);
    }

    /**
     * Resolves the file URI used to read embedded lyrics: prefer a known local URI, otherwise look
     * the on-device file up in the MediaStore by title/artist/duration.
     */
    @Nullable
    private Uri localUriFor(TrackInfo track) {
        if (isLocalUri(currentMediaUri)) {
            return currentMediaUri;
        }
        if (currentMediaUri != null) {
            return null;
        }
        return LocalLyricsFetcher.resolveMediaStoreUri(
                track.title(), track.artist(), track.durationSeconds(), currentRawTitle, currentRawArtist);
    }

    private record VariantQuery(TrackInfo track, int penalty) {}

    /** The two query tiers of a lookup: exact metadata first, spelling variants after. */
    private record Tiers(List<VariantQuery> original, List<VariantQuery> derived) {}

    /**
     * Builds the query tiers for a lookup.
     */
    private Tiers buildTiers(TrackInfo track, @Nullable String rawTitle) {
        if (LyricsRequests.isCustomMatchMode()) {
            track = new TrackInfo(track.title(), track.artist(), "", 0);
        }
        List<VariantQuery> originalTier = new ArrayList<>();
        List<VariantQuery> derivedTier = new ArrayList<>();
        originalTier.add(new VariantQuery(track, 0));
        for (TrackInfo v : CharactersConverter.variants(track)) {
            derivedTier.add(new VariantQuery(v, 1));
        }

        if (track.artist() != null) {
            String[] splitArtists = MetadataCleaner.splitArtists(track.artist());
            for (String artist : splitArtists) {
                if (!artist.equals(track.artist())) {
                    derivedTier.add(new VariantQuery(new TrackInfo(
                            track.title(), artist, track.album(), track.durationSeconds()), 2));
                }
            }
        }

        TrackInfo trusted = MetadataCleaner.trustedDashSplit(
                rawTitle, track.artist(), track.album(), track.durationSeconds());
        if (trusted != null && !trusted.equals(track)) {
            originalTier.add(new VariantQuery(trusted, 1));
        }

        TrackInfo dashSplit = MetadataCleaner.anyDashSplit(
                rawTitle, track.album(), track.durationSeconds());
        if (dashSplit != null && !dashSplit.equals(track) && !dashSplit.equals(trusted)) {
            derivedTier.add(new VariantQuery(dashSplit, 2));
        }
        TrackInfo dashSplitRev = MetadataCleaner.anyDashSplitReversed(
                rawTitle, track.album(), track.durationSeconds());
        if (dashSplitRev != null && !dashSplitRev.equals(track)
                && !dashSplitRev.equals(trusted) && !dashSplitRev.equals(dashSplit)) {
            derivedTier.add(new VariantQuery(dashSplitRev, 2));
        }
        return new Tiers(originalTier, derivedTier);
    }

    private record LookupResult(@Nullable Lyrics best,
                                List<ScoredCandidate> scored,
                                Set<String> providersWithResults,
                                Set<String> providersFailed,
                                Set<String> providersAttempted,
                                boolean queueFillPending) {}

    private LookupResult fetchFromProviders(int id, List<VariantQuery> originalTier,
                                            List<VariantQuery> derivedTier,
                                            boolean[] failed,
                                            List<LyricsProvider> providers,
                                            boolean exhaustive) {
        final boolean wordSync = Settings.LYRICS_WORD_SYNC.get();
        final long start = SystemClock.uptimeMillis();
        final long stage1Deadline = start + 2_000;
        final long stage2Deadline = start + 4_000;
        final long stage3Deadline = start + 9_000;

        List<LyricsProvider> nonBlind = new ArrayList<>(providers.size());
        List<LyricsProvider> blind = new ArrayList<>();
        for (LyricsProvider p : providers) {
            if (isBlindProvider(p)) {
                blind.add(p);
            } else {
                nonBlind.add(p);
            }
        }
        final int stage1Count = Math.min(4, nonBlind.size());
        final List<LyricsProvider> stage1Providers = nonBlind.subList(0, stage1Count);
        final List<LyricsProvider> stage2Providers = nonBlind.subList(
                stage1Count, nonBlind.size());

        CompletionService<ProviderFetch> cs = new ExecutorCompletionService<>(fetchExecutor);
        List<Future<ProviderFetch>> futures = new ArrayList<>();
        Set<String> providersWithResults = ConcurrentHashMap.newKeySet();
        Set<String> providersFailed = ConcurrentHashMap.newKeySet();
        Set<String> providersAttempted = ConcurrentHashMap.newKeySet();
        AtomicBoolean threadFailed = new AtomicBoolean(false);

        List<ScoredCandidate> scoreCandidates = new ArrayList<>();
        final DisplayState display = new DisplayState(wordSync, exhaustive);

        for (VariantQuery vq : originalTier) {
            for (LyricsProvider provider : stage1Providers) {
                futures.add(submitFetch(id, cs, () -> fetchOne(provider, vq, providersAttempted,
                        providersWithResults, providersFailed, threadFailed)));
            }
        }

        int completed = 0;
        boolean stage2Submitted = false;
        boolean stage3Submitted = false;
        long endgameAt = start + 5_000;

        completed = pollStage(id, cs, futures, completed, stage1Deadline, display, scoreCandidates,
                endgameAt, true);

        if (id == requestId.get() && !display.windowClosed && !stage2Providers.isEmpty()) {
            stage2Submitted = true;
            for (VariantQuery vq : originalTier) {
                for (LyricsProvider provider : stage2Providers) {
                    futures.add(submitFetch(id, cs, () -> fetchOne(provider, vq, providersAttempted,
                            providersWithResults, providersFailed, threadFailed)));
                }
            }
            completed = pollStage(id, cs, futures, completed, stage2Deadline, display,
                    scoreCandidates, endgameAt, true);
        }

        if (id == requestId.get() && !display.windowClosed) {
            stage3Submitted = true;
            for (VariantQuery vq : derivedTier) {
                for (LyricsProvider provider : nonBlind) {
                    futures.add(submitFetch(id, cs, () -> fetchOne(provider, vq, providersAttempted,
                            providersWithResults, providersFailed, threadFailed)));
                }
                for (LyricsProvider provider : blind) {
                    futures.add(submitFetch(id, cs, () -> fetchOne(provider, vq, providersAttempted,
                            providersWithResults, providersFailed, threadFailed)));
                }
            }
            for (VariantQuery vq : originalTier) {
                for (LyricsProvider provider : blind) {
                    futures.add(submitFetch(id, cs, () -> fetchOne(provider, vq, providersAttempted,
                            providersWithResults, providersFailed, threadFailed)));
                }
            }
            completed = pollStage(id, cs, futures, completed, stage3Deadline, display,
                    scoreCandidates, endgameAt, false);
        }

        Future<ProviderFetch> extra;
        while ((extra = cs.poll()) != null) {
            completed++;
            try {
                display.accept(extra.get(), scoreCandidates, false);
            } catch (Exception ex) {
                Logger.printDebug(() -> "Failed to process extra lyrics result", ex);
            }
        }

        failed[0] = threadFailed.get();
        finishFetches(futures);

        Lyrics bestResult = display.bestResult;
        if (bestResult == null && display.bestFallback != null) {
            bestResult = display.bestFallback;
        }
        if (bestResult == null && !scoreCandidates.isEmpty()) {
            scoreCandidates.sort(null);
            for (ScoredCandidate sc : scoreCandidates) {
                if (sc.lyrics() != null && !sc.lyrics().isEmpty()
                        && sc.lyrics() != Lyrics.NOT_FOUND) {
                    bestResult = sc.lyrics();
                    break;
                }
            }
        }

        scoreCandidates.sort(null);
        final boolean stage2Needed = !stage2Providers.isEmpty();
        final boolean queueFillPending = display.windowClosed
                && ((stage2Needed && !stage2Submitted) || !stage3Submitted);
        return new LookupResult(bestResult, scoreCandidates,
                providersWithResults, providersFailed, providersAttempted, queueFillPending);
    }

    private int pollStage(int id, CompletionService<ProviderFetch> cs,
                          List<Future<ProviderFetch>> futures,
                          int completed,
                          long deadline,
                          DisplayState display,
                          List<ScoredCandidate> scoreCandidates,
                          long endgameAt,
                          boolean stage12) {
        while (completed < futures.size()) {
            if (id != requestId.get() || Thread.currentThread().isInterrupted()) {
                display.windowClosed = true;
                return completed;
            }
            long now = SystemClock.uptimeMillis();
            if (!display.exhaustive && now >= endgameAt && display.bestResult == null
                    && display.bestFallback != null) {
                display.bestResult = display.bestFallback;
                display.windowClosed = true;
                return completed;
            }
            long remaining = deadline - now;
            if (remaining <= 0) {
                break;
            }
            long wait = now < endgameAt ? Math.min(remaining, endgameAt - now) : remaining;
            Future<ProviderFetch> f;
            try {
                // Recheck stale requests every 100 ms, trading about 10 idle wakeups/s for prompt cancellation.
                // Completed results wake poll immediately; provider timeouts and stage deadlines are unchanged.
                f = cs.poll(Math.min(wait, 100), TimeUnit.MILLISECONDS);
            } catch (InterruptedException ex) {
                Logger.printDebug(() -> "Interrupted polling provider futures", ex);
                Thread.currentThread().interrupt();
                break;
            }
            if (f == null) {
                if (!display.exhaustive && now >= endgameAt && display.bestResult == null) {
                    if (display.bestFallback != null) {
                        display.bestResult = display.bestFallback;
                    } else {
                        scoreCandidates.sort(null);
                        for (ScoredCandidate sc : scoreCandidates) {
                            if (sc.lyrics() != null && !sc.lyrics().isEmpty()
                                    && sc.lyrics() != Lyrics.NOT_FOUND) {
                                display.bestResult = sc.lyrics();
                                break;
                            }
                        }
                    }
                    if (display.bestResult != null) {
                        display.windowClosed = true;
                        return completed;
                    }
                }
                continue;
            }
            completed++;
            try {
                boolean complete = display.accept(f.get(), scoreCandidates, stage12);
                Lyrics available = display.bestResult != null
                        ? display.bestResult : display.bestFallback;
                // Word timing is an upgrade, not a reason to leave a usable lyric blank.
                if (available != null && available != display.published) {
                    display.published = available;
                    publishFromLookup(id, available);
                }
                if (complete) {
                    display.windowClosed = true;
                    return completed;
                }
            } catch (Exception ex) {
                Logger.printDebug(() -> "Could not fetch lyrics", ex);
            }
        }
        return completed;
    }

    private static final class DisplayState {
        final boolean wordSync;
        /**
         * Keeps the lookup from closing its display window early: an explicit search waits
         * for every stage so that the best result of the whole run is the one that shows.
         */
        final boolean exhaustive;
        @Nullable Lyrics published;
        @Nullable Lyrics bestResult;
        int bestDisplayRank;
        @Nullable Lyrics bestFallback;
        int bestFallbackRank;
        boolean windowClosed;

        DisplayState(boolean wordSync, boolean exhaustive) {
            this.wordSync = wordSync;
            this.exhaustive = exhaustive;
            this.bestDisplayRank = wordSync ? -1 : -2;
            // Plain (rank 0) high-match must qualify as endgame fallback; default 0 would not.
            this.bestFallbackRank = -1;
        }

        boolean accept(@Nullable ProviderFetch pf, List<ScoredCandidate> scoreCandidates,
                       boolean stage12) {
            if (pf == null || (!pf.highMatch() && !pf.blind())) {
                return false;
            }
            Lyrics fetched = pf.lyrics();
            if (!isValidLyrics(fetched, null)) {
                return false;
            }
            final int match = pf.matchScore();
            final int rank = LyricsRequests.syncRank(fetched);
            final int composite = LyricsRequests.composite(match, fetched, pf.penalty());
            scoreCandidates.add(new ScoredCandidate(composite, rank, fetched));

            if (LyricsRequests.isCustomMatchMode()) {
                if (composite > bestFallbackRank) {
                    bestFallback = fetched;
                    bestFallbackRank = composite;
                }
                if (composite > bestDisplayRank) {
                    bestResult = fetched;
                    bestDisplayRank = composite;
                }
                return false;
            }

            if (!pf.highMatch()) {
                return false;
            }

            if (rank > bestFallbackRank) {
                bestFallback = fetched;
                bestFallbackRank = rank;
            }

            if (wordSync) {
                if (rank == 2) {
                    bestResult = fetched;
                    return !exhaustive;
                }
                return false;
            }

            final int effectiveRank = rank == 2 ? -1 : rank;
            if (effectiveRank > bestDisplayRank) {
                bestResult = fetched;
                bestDisplayRank = effectiveRank;
            }
            return !exhaustive && stage12 && effectiveRank >= 1 && bestResult != null;
        }
    }

    private static boolean isBlindProvider(LyricsProvider provider) {
        return switch (provider.name()) {
            case "Spotify", "AMLL", "bLyrics", "BiniLyrics", "Lyricify" -> true;
            default -> false;
        };
    }

    private record ProviderFetch(@Nullable Lyrics lyrics, int matchScore, boolean highMatch,
                                 boolean blind, int penalty) {}

    private ProviderFetch fetchOne(LyricsProvider provider, VariantQuery vq,
                                   Set<String> attempted, Set<String> withResults,
                                   Set<String> failedSet, AtomicBoolean threadFailed) {
        attempted.add(provider.name());
        try {
            LyricsProvider.FetchResult fr = provider.fetch(vq.track());
            if (fr == null || fr.lyrics() == null || fr.lyrics() == Lyrics.NOT_FOUND) {
                return null;
            }
            withResults.add(provider.name());
            TrackInfo query = vq.track();
            return new ProviderFetch(fr.lyrics(), fr.matchScore(query), fr.isHighMatch(query),
                    fr.isBlind(), vq.penalty());
        } catch (InterruptedException | java.util.concurrent.CancellationException ex) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception ex) {
            if (Thread.currentThread().isInterrupted()) {
                return null;
            }
            Logger.printDebug(() -> "Provider fetch failed: " + provider.name(), ex);
            failedSet.add(provider.name());
            threadFailed.set(true);
            return null;
        }
    }

    private void publishFromLookup(int id, Lyrics lyrics) {
        if (id != requestId.get()) {
            return;
        }
        Lyrics display;
        try {
            display = prepareForDisplay(lyrics);
        } catch (Throwable ignored) {
            display = lyrics;
        }
        final Lyrics raw = lyrics;
        final Lyrics prepared = display;
        Utils.runOnMainThread(() -> {
            Lyrics current = currentLyrics;
            if (id == requestId.get() && state == State.LOADED
                    && current != null && prepared != null
                    && fingerprint(current).equals(fingerprint(prepared))) {
                return;
            }
            publish(id, raw, prepared);
        });
    }

    private void publish(int id, Lyrics raw, Lyrics prepared) {
        try {
            publishPrepared(id, raw, prepared);
        } catch (Throwable ignored) {
            if (id == requestId.get()) {
                Lyrics current = currentLyrics;
                if (current != null && !current.isEmpty()) {
                    setState(State.LOADED, current);
                } else {
                    setState(State.NOT_FOUND, null);
                }
            }
        }
    }

    private void publishPrepared(int id, Lyrics raw, Lyrics prepared) {
        if (id != requestId.get()) {
            return;
        }

        if (suppressForRequest == id) {
            if (raw != null && raw != Lyrics.NOT_FOUND && !raw.isEmpty()) {
                enqueueWhileSuppressed(raw, prepared);
            }
            return;
        }

        String incomingFp = null;
        if (raw != null && raw != Lyrics.NOT_FOUND && !raw.isEmpty()) {
            incomingFp = fingerprint(raw);
        }

        if (prepared == null || prepared == Lyrics.NOT_FOUND || prepared.isEmpty()) {
            // Nothing was found, so the last lyric the user landed on stays the opening one.
            Lyrics current = currentLyrics;
            if (current != null && !current.isEmpty()) {
                setState(State.LOADED, current);
            } else {
                setState(State.NOT_FOUND, null);
            }
        } else {
            if (incomingFp != null) {
                shownFingerprints.add(incomingFp);
            }
            shownFingerprints.add(fingerprint(prepared));
            setState(State.LOADED, prepared);
            LyricsPanelInstaller.enableLyricsButton();
            Utils.runOnMainThreadDelayed(LyricsPanelInstaller::onLyricsPanelDetected, 300);
            rememberPreference(prepared);
        }
    }

    @Nullable
    public String skipSegmentsVideoId() {
        String videoId = currentVideoId;
        if (videoId.isEmpty() || isLocalUri(currentMediaUri)) {
            return null;
        }
        PlaylistRequest.Song song = PlayAlbumSongsPatch.getSong(videoId);
        String resolved = song == null ? videoId : song.videoId();
        return (resolved == null || resolved.isEmpty()) ? null : resolved;
    }

    /**
     * The candidate segment the open submit dialog wants previewed. Playback time counts
     * it like a stored segment - the lyrics skip it - until {@link #clearDialogSegment()}
     * runs when the dialog leaves. Called again on every A-B change of that dialog.
     */
    public void setDialogSegment(@NonNull String videoId, long startMs, long endMs) {
        if (endMs <= startMs) {
            clearDialogSegment();
            return;
        }
        dialogPreviewVideoId = videoId;
        dialogPreviewSeg = new SkipSegments.Seg(startMs, endMs, "music_offtopic", 0);
    }

    /** Drops the dialog preview so playback time follows the stored segments again. */
    public void clearDialogSegment() {
        dialogPreviewSeg = null;
        dialogPreviewVideoId = null;
    }

    public void clearDialogSegment(@NonNull String videoId, long startMs, long endMs) {
        final SkipSegments.Seg seg = dialogPreviewSeg;
        if (seg != null && videoId.equals(dialogPreviewVideoId)
                && seg.startMs() == startMs && seg.endMs() == endMs) {
            clearDialogSegment();
        }
    }

    /**
     * The segments playback time is stripped of before it is compared with lyric
     * timestamps. Null while the segment switch is off - nothing is read or requested
     * then - or when the current lyrics are written against the video timeline
     * themselves or playback is local. Every frame reads the freshest answer: an own fetch
     * landing in the cache replaces what the SponsorBlock controller provided before it,
     * and the previous latch only serves while no answer exists at all - no path that
     * reads the position ever waits for one. An open submit dialog's candidate folds
     * into the answer so its A-B range skips live, before anything is submitted.
     */
    @Nullable
    private List<SkipSegments.Seg> syncSegs() {
        if (!Settings.LYRICS_SB_MATCHING.get()) {
            return null;
        }
        if (isVideoIdMatchedLyrics(currentLyrics)) {
            return null;
        }
        final String videoId = skipSegmentsVideoId();
        if (videoId == null) {
            return null;
        }
        final TrackInfo track = currentTrack;
        List<SkipSegments.Seg> base = SkipSegments.peek(videoId,
                track == null ? 0 : track.durationSeconds() * 1000L);
        if (base != null) {
            displaySegsVideoId = videoId;
            displaySegs = base;
        } else {
            final List<SkipSegments.Seg> latched = displaySegs;
            base = latched != null && videoId.equals(displaySegsVideoId) ? latched : null;
            if (base == null) {
                SkipSegments.prefetch(videoId);
            }
        }
        final SkipSegments.Seg preview = dialogPreviewSeg;
        if (preview == null || !videoId.equals(dialogPreviewVideoId)) {
            return base;
        }
        return SkipSegments.withSegment(base, preview);
    }

    /**
     * Whether the current lyrics were fetched by video id and are therefore written
     * against the video timeline the player reports. For those sources playback time
     * already is their clock: the SponsorBlock segments must not be subtracted from it,
     * neither when a position is read nor when a seek is mapped back. The name set covers
     * every provider that resolves a video id; results matched by title always come from
     * the released audio and always get the subtraction.
     */
    private static boolean isVideoIdMatchedLyrics(@Nullable Lyrics lyrics) {
        if (lyrics == null) {
            return false;
        }
        String provider = lyrics.providerName();
        return Lyrics.CAPTIONS_PROVIDER.equals(provider)
                || "Unison".equals(provider)
                || "SimpMusic".equals(provider)
                || (provider != null && provider.startsWith("YouTube Music"));
    }

    /**
     * Maps a lyric timestamp onto the video timeline a seek has to target: the inverse of
     * the subtraction {@link #getPositionMs()} applies before it reads a lyric time.
     */
    public long toVideoTimeMs(long lyricTimeMs) {
        final List<SkipSegments.Seg> segs = syncSegs();
        if (segs == null || segs.isEmpty()) {
            return lyricTimeMs;
        }
        return SkipSegments.remapTimestamp(lyricTimeMs, segs);
    }

    /**
     * The track the provider search asks with: the released audio's duration, the video
     * minus the sponsorblock segments, so candidates written against it pass the duration
     * filter. The user's choice ({@link Settings#LYRICS_SB_MATCHING}) gates this and the
     * rest of the segment pipeline alike: with it off, nothing is requested, the search
     * keeps the video duration, and playback time is never corrected.
     */
    @NonNull
    private static TrackInfo withEffectiveDuration(@NonNull TrackInfo track,
                                                   @Nullable List<SkipSegments.Seg> segs) {
        if (!Settings.LYRICS_SB_MATCHING.get()) {
            return track;
        }
        if (segs == null || segs.isEmpty() || track.durationSeconds() <= 0) {
            return track;
        }
        long contentMs = SkipSegments.effectiveDurationMs(track.durationSeconds() * 1000L, segs);
        int contentSeconds = (int) (contentMs / 1000);
        if (contentSeconds <= 0 || contentSeconds == track.durationSeconds()) {
            return track;
        }
        return new TrackInfo(track.title(), track.artist(), track.album(), contentSeconds);
    }

    /**
     * Filters out credit lines and text the user asked to hide, then normalizes the timings
     * of synced lyrics, consulting the short-lived cache of already filtered results.
     */
    private Lyrics prepareForDisplay(Lyrics lyrics) {
        String cacheKey = null;
        if (lyrics != null && lyrics != Lyrics.NOT_FOUND && !lyrics.isEmpty()) {
            StringBuilder sb = new StringBuilder(lyrics.providerName());
            List<LyricsLine> lines = lyrics.lines();
            int limit = Math.min(3, lines.size());
            for (int i = 0; i < limit; i++) {
                sb.append('|').append(lines.get(i).text());
            }
            cacheKey = sb.toString();
            Lyrics cached = filteredCache.get(cacheKey);
            if (cached != null) {
                lyrics = cached;
            }
        }

        if (cacheKey == null || lyrics != filteredCache.get(cacheKey)) {
            lyrics = filterCreditLines(lyrics, currentTrack);
            lyrics = filterLyricsText(lyrics);
            if (cacheKey != null) {
                filteredCache.put(cacheKey, lyrics);
            }
        }
        if (lyrics != null && lyrics.synced() && !lyrics.isEmpty()) {
            lyrics = new Lyrics(
                    Lyrics.clampLastWordEnds(
                            Lyrics.fixAnomalousWordTimestamps(lyrics.lines())),
                    lyrics.providerName(), true, lyrics.romanization(),
                    lyrics.translations(), lyrics.romanizations(),
                    lyrics.songwriters(), lyrics.rawFormat(),
                    lyrics.formatType(), lyrics.sourceUrl());
        }
        return lyrics;
    }

    private static Lyrics filterCreditLines(Lyrics lyrics, TrackInfo track) {
        if (lyrics == null || lyrics == Lyrics.NOT_FOUND || lyrics.isEmpty()) {
            return lyrics;
        }

        List<LyricsLine> original = lyrics.lines();
        int size = original.size();

        boolean[] isCredit = new boolean[size];
        for (int i = 0; i < size; i++) {
            String text = original.get(i).text().trim();
            isCredit[i] = isCreditLine(text, track);
        }

        int startBlockEnd = -1;
        int consecutiveNonCredit = 0;
        for (int i = 0; i < size; i++) {
            if (isCredit[i]) {
                startBlockEnd = i;
                consecutiveNonCredit = 0;
            } else {
                if (++consecutiveNonCredit > CREDIT_LINE_GAP_TOLERANCE) {
                    break;
                }
            }
        }

        int endBlockStart = size;
        consecutiveNonCredit = 0;
        for (int i = size - 1; i >= 0; i--) {
            if (isCredit[i]) {
                endBlockStart = i;
                consecutiveNonCredit = 0;
            } else {
                if (++consecutiveNonCredit > CREDIT_LINE_GAP_TOLERANCE) {
                    break;
                }
            }
        }

        boolean filterFirstLine = false;
        if (startBlockEnd >= 1 && !isCredit[0]) {
            filterFirstLine = true;
            for (int i = 1; i <= startBlockEnd; i++) {
                if (!isCredit[i]) {
                    filterFirstLine = false;
                    break;
                }
            }
        }

        boolean[] kept = new boolean[size];
        List<String> creditLines = new ArrayList<>();
        List<LyricsLine> filteredLines = new ArrayList<>(size);

        for (int i = 0; i < size; i++) {
            LyricsLine line = original.get(i);
            String text = line.text().trim();
            final boolean inStartBlock = i <= startBlockEnd;
            final boolean inEndBlock = i >= endBlockStart;
            final boolean shouldFilter = (inStartBlock || inEndBlock) && isCredit[i]
                    || (inStartBlock && i == 0 && filterFirstLine);

            if (shouldFilter) {
                if (!text.isEmpty()) {
                    creditLines.add(text);
                }
            } else {
                kept[i] = true;
                filteredLines.add(line);
            }
        }

        if (filteredLines.size() == size) {
            return lyrics;
        }

        List<LyricsLine> romanization = filterAlignedList(lyrics.romanization(), kept);
        Map<String, List<LyricsLine>> translations = filterAlignedMap(lyrics.translations(), kept);
        Map<String, List<LyricsLine>> romanizations = filterAlignedMap(lyrics.romanizations(), kept);

        List<String> songwriters = new ArrayList<>();
        for (String cl : creditLines) {
            if (!songwriters.contains(cl)) {
                songwriters.add(cl);
            }
        }
        List<String> existingSongwriters = lyrics.songwriters();
        if (existingSongwriters != null) {
            for (String sw : existingSongwriters) {
                if (!songwriters.contains(sw)) {
                    songwriters.add(sw);
                }
            }
        }

        return new Lyrics(filteredLines, lyrics.providerName(), lyrics.synced(),
                romanization, translations, romanizations,
                songwriters, lyrics.rawFormat(), lyrics.formatType(), lyrics.sourceUrl());
    }

    private static List<LyricsLine> filterAlignedList(List<LyricsLine> aligned, boolean[] kept) {
        if (aligned == null || aligned.isEmpty()) {
            return aligned;
        }
        List<LyricsLine> result = new ArrayList<>(aligned.size());
        for (int i = 0; i < aligned.size() && i < kept.length; i++) {
            if (kept[i]) {
                result.add(aligned.get(i));
            }
        }
        return result;
    }

    private static Map<String, List<LyricsLine>> filterAlignedMap(
            Map<String, List<LyricsLine>> map, boolean[] kept) {
        if (map == null || map.isEmpty()) {
            return map;
        }
        Map<String, List<LyricsLine>> result = new HashMap<>(2 * map.size());
        for (Map.Entry<String, List<LyricsLine>> entry : map.entrySet()) {
            result.put(entry.getKey(), filterAlignedList(entry.getValue(), kept));
        }
        return result;
    }

    private static int countSlashes(String text) {
        int count = 0;
        for (int i = 0, length = text.length(); i < length; i++) {
            if (text.charAt(i) == '/') {
                count++;
            }
        }
        return count;
    }

    private static boolean isCreditLine(String text, TrackInfo track) {
        if (text == null || text.isEmpty()) {
            return true;
        }
        text = text.replaceAll("\\s{2,}", " ");
        if (text.length() <= MAX_ARTIST_SONG_LINE_LENGTH
                && ARTIST_SONG_PATTERN.matcher(text.trim()).matches()
                && isArtistSongLine(text, track)) {
            return true;
        }
        if (countSlashes(text) > 5) {
            return true;
        }
        String setting = MetadataCleaner.resolveSetting(Settings.LYRICS_CREDIT_LINE_REGEX.get());
        if (setting.trim().isEmpty()) {
            return false;
        }

        String normalized = CharactersConverter.normalize(text);

        String[] markers = setting.split(",");
        List<String> allVariants = getCachedCreditVariants(markers);

        for (String variant : allVariants) {
            if (variant.length() >= 2 && normalized.startsWith(variant)) {
                int end = variant.length();
                if (end >= normalized.length()
                        || isCreditLabelBoundary(normalized, end, allVariants, variant)) {
                    return true;
                }
            }
        }

        String normalizedWithSpaces = normalized;

        normalized = normalized
                .replace('|', ':')
                .replace('｜', ':')
                .replace('—', ':')
                .replace('－', ':')
                .replace(';', ':')
                .replace('；', ':')
                .replace(',', ':')
                .replace('，', ':')
                .replace('~', ':')
                .replace('～', ':')
                .replace(' ', ':')
                .replace('：', ':')
                .replace('·', ':')
                .replace('@', ':')
                .replace('/', ':')
                .replace('\\', ':')
                .replace('&', ':')
                .replaceAll("\\s+:", ":");

        int sepIdx = normalized.indexOf(':');
        if (sepIdx >= 0) {
            if (normalized.substring(sepIdx + 1).trim().isEmpty()) {
                return false;
            }
        }
        String beforeSep = sepIdx >= 0 ? normalized.substring(0, sepIdx) : normalized;

        boolean hasRealSeparator = hasRealSeparator(sepIdx, normalizedWithSpaces);

        for (String variant : allVariants) {
            if (variant.isEmpty()) {
                continue;
            }
            if (hasRealSeparator && beforeSep.equals(variant)) {
                return true;
            }
            if (sepIdx >= 0 && beforeSep.endsWith(variant)) {
                int startIdx = beforeSep.length() - variant.length();
                if (startIdx > 0) {
                    char prev = beforeSep.charAt(startIdx - 1);
                    if (!Character.isLetterOrDigit(prev) && prev != '\'') {
                        return true;
                    }
                }
            }
            if (variant.length() >= 2 && LyricsRequests.isCjk(variant.charAt(0))
                    && beforeSep.startsWith(variant)) {
                int end = variant.length();
                if (end >= beforeSep.length() || isCreditLabelBoundary(beforeSep, end, allVariants, variant)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasRealSeparator(int sepIdx, String normalizedWithSpaces) {
        if (sepIdx < 0) return false;
        int realSepIdx = -1;
        int spaceSepIdx = normalizedWithSpaces.indexOf(' ');
        int colonSepIdx = normalizedWithSpaces.indexOf(':');
        if (colonSepIdx >= 0) {
            realSepIdx = colonSepIdx;
        }
        for (char c : new char[]{'|', '｜', '—', '－', '-', ';', '；', ',', '，', '~', '～',
                '：', '·', '@', '/', '\\', '&'}) {
            int idx = normalizedWithSpaces.indexOf(c);
            if (idx >= 0 && (realSepIdx < 0 || idx < realSepIdx)) {
                realSepIdx = idx;
            }
        }
        return realSepIdx >= 0 && (spaceSepIdx < 0 || realSepIdx < spaceSepIdx);
    }

    private static List<String> getCachedCreditVariants(String[] markers) {
        String markerKey = String.join(",", markers);
        List<String> cached = cachedCreditVariants;
        if (cached != null && markerKey.equals(cachedCreditMarkers)) {
            return cached;
        }
        Set<String> allVariantsSet = new LinkedHashSet<>();
        for (String marker : markers) {
            marker = marker.trim();
            if (!marker.isEmpty()) {
                allVariantsSet.addAll(CharactersConverter.variants(marker));
            }
        }
        List<String> result = new ArrayList<>(allVariantsSet);
        cachedCreditMarkers = markerKey;
        cachedCreditVariants = result;
        return result;
    }

    private static boolean isCreditLabelBoundary(String text, int pos, List<String> allVariants, String currentVariant) {
        if (pos >= text.length()) {
            return true;
        }
        char c = text.charAt(pos);
        if (c == '/' || c == '&' || c == '|'
                || c == '·' || c == '~' || c == '@' || c == '\\'
                || c == '－' || c == '—' || c == '-' || c == ':'
                || c == '、' || c == '；' || c == '，' || c == ','
                || c == ';' || c == '＋' || c == '+') {
            if (c == '-' || c == '－' || c == '—') {
                char before = pos > 0 ? text.charAt(pos - 1) : 0;
                char after = pos + 1 < text.length() ? text.charAt(pos + 1) : 0;
                if (isLatinLetterOrDigit(before) && isLatinLetterOrDigit(after)) {
                    return false;
                }
                return !text.regionMatches(true, pos + 1, currentVariant, 0, currentVariant.length());
            }
            return true;
        }
        if (Character.isWhitespace(c)) {
            if (pos > 0 && (LyricsRequests.isCjk(text.charAt(pos - 1))
                    || currentVariant.indexOf(' ') >= 0)) {
                return true;
            }
        }
        if (c == '和' || c == '与' || c == '及') {
            return true;
        }
        if (pos > 0) {
            char prev = text.charAt(pos - 1);
            if (LyricsRequests.isCjk(prev) != LyricsRequests.isCjk(c) && Character.isLetterOrDigit(c)) {
                return true;
            }
        }
        String remainder = text.substring(pos);
        for (String v : allVariants) {
            if (v.equals(currentVariant)) {
                continue;
            }
            if (v.length() >= 2 && remainder.startsWith(v)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isLatinLetterOrDigit(char c) {
        return Character.isLetterOrDigit(c) && !LyricsRequests.isCjk(c);
    }

    private static boolean isArtistSongLine(String text, TrackInfo track) {
        if (track == null) {
            return false;
        }
        String artist = track.artist();
        String title = track.title();
        if (artist == null || artist.trim().isEmpty() || title == null || title.trim().isEmpty()) {
            return false;
        }
        int dashIdx = text.indexOf('-');
        if (dashIdx <= 0 || dashIdx >= text.length() - 1) {
            return false;
        }
        List<String> artistVariants = CharactersConverter.variants(artist.trim());
        if (artistVariants.isEmpty()) {
            return false;
        }
        List<String> titleVariants = CharactersConverter.variants(title.trim());
        if (titleVariants.isEmpty()) {
            return false;
        }
        List<String> leftVariants = CharactersConverter.variants(text.substring(0, dashIdx).trim());
        List<String> rightVariants = CharactersConverter.variants(text.substring(dashIdx + 1).trim());
        return (containsAnyVariant(leftVariants, artistVariants) && containsAnyVariant(rightVariants, titleVariants))
                || (containsAnyVariant(leftVariants, titleVariants) && containsAnyVariant(rightVariants, artistVariants));
    }

    private static boolean containsAnyVariant(List<String> haystacks, List<String> needles) {
        for (String haystack : haystacks) {
            for (String needle : needles) {
                if (haystack.contains(needle) || needle.contains(haystack)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Applies {@link Settings#LYRICS_TEXT_FILTER} to the original lyrics text only.
     * Translations and romanizations are left untouched, and a line the filter does not
     * fire on keeps its provider text rather than a normalized copy, so fullwidth
     * punctuation is not folded to ASCII.
     */
    private static Lyrics filterLyricsText(Lyrics lyrics) {
        if (lyrics == null || lyrics == Lyrics.NOT_FOUND) {
            return lyrics;
        }
        String filter = MetadataCleaner.resolveSetting(Settings.LYRICS_TEXT_FILTER.get());
        if (filter.trim().isEmpty()) {
            return lyrics;
        }

        List<LyricsLine> original = lyrics.lines();
        List<LyricsLine> filtered = new ArrayList<>(original.size());
        boolean anyDropped = false;
        for (LyricsLine line : original) {
            String text = MetadataCleaner.applyRegexPreserveOriginal(line.text(), filter)
                    .trim().replaceAll("\\s+", " ");
            if (text.isEmpty()) {
                anyDropped = true;
                continue;
            }
            if (text.equals(line.text().trim().replaceAll("\\s+", " "))) {
                filtered.add(line);
                continue;
            }
            if (line.hasWords()) {
                List<Word> words = new ArrayList<>(line.words().size());
                for (Word w : line.words()) {
                    String wt = MetadataCleaner.applyRegexPreserveOriginal(w.text(), filter)
                            .trim().replaceAll("\\s+", " ");
                    if (!wt.isEmpty()) {
                        words.add(new Word(w.startMs(), w.endMs(), wt,
                                w.romaji(), w.endsWithSpace()));
                    }
                }
                filtered.add(new LyricsLine(line.startTimeMs(), text, words));
            } else {
                filtered.add(new LyricsLine(line.startTimeMs(), text));
            }
        }

        if (!anyDropped) {
            return lyrics;
        }

        return new Lyrics(filtered, lyrics.providerName(), lyrics.synced(),
                null, null, null, lyrics.songwriters(), lyrics.rawFormat(), lyrics.formatType(),
                lyrics.sourceUrl());
    }

    private void setErrorStateIfCurrent(int id) {
        Utils.runOnMainThread(() -> {
            if (id == requestId.get()) {
                setState(State.ERROR, null);
            }
        });
    }

    private void scheduleQueueFill(int id, boolean pending, List<VariantQuery> original,
                                   List<VariantQuery> derived, List<LyricsProvider> providers) {
        if (id != requestId.get() || !pending) {
            return;
        }
        final List<VariantQuery> fillOriginal = List.copyOf(original);
        final List<VariantQuery> fillDerived = List.copyOf(derived);
        final List<LyricsProvider> fillProviders = List.copyOf(providers);
        runOnLookupThread(
                () -> fillQueueInBackground(id, fillOriginal, fillDerived, fillProviders));
    }

    private void seedCandidateQueue(int id, List<ScoredCandidate> scored, boolean validResult,
                                    @Nullable Lyrics result) {
        synchronized (candidateQueue) {
            if (id == requestId.get()) {
                candidateQueue.clear();
                for (ScoredCandidate sc : scored) {
                    if (validResult && sc.lyrics() == result) {
                        continue;
                    }
                    if (!shownFingerprints.contains(fingerprint(sc.lyrics()))) {
                        candidateQueue.add(sc);
                    }
                }
                phase2Done = false;
            }
        }
        reorderQueueByRemembered();
    }

    private void setState(State newState, @Nullable Lyrics lyrics) {
        if (newState == State.ERROR || newState == State.NOT_FOUND) {
            if (suppressForRequest == requestId.get()) {
                // The lyric on screen was chosen by the user; a failed lookup keeps it there.
                return;
            }
        }
        state = newState;
        currentLyrics = lyrics;

        // A listener may remove itself while being notified.
        for (Listener listener : new ArrayList<>(listeners)) {
            try {
                listener.onLyricsChanged(newState, lyrics);
            } catch (Exception ex) {
                Logger.printException(() -> "Lyrics listener failure", ex);
            }
        }

        if (newState == State.LOADED) {
            // A panel is only detected while the app builds its own lyrics into it, which it
            // never does for a music video, so an open panel is covered from here instead.
            LyricsPanelInstaller.onLyricsPanelDetected();
            LyricsPanelInstaller.enableLyricsButton();
        }
    }

    @NonNull
    public String getCurrentLineText() {
        Lyrics lyrics = currentLyrics;
        if (lyrics == null || !lyrics.synced() || lyrics.isEmpty()) {
            return "";
        }
        final int index = lyrics.indexForPosition(getPositionMs(), lastHighlightedIndex);
        lastHighlightedIndex = index;
        if (index < 0) {
            return "";
        }
        String text = lyrics.lines().get(index).text();
        return text == null ? "" : text;
    }

    public boolean areLyricsSynced() {
        Lyrics lyrics = currentLyrics;
        return lyrics != null && lyrics.synced() && !lyrics.isEmpty();
    }

    @NonNull
    private static List<LyricsProvider> providersInOrder(String order) {
        List<LyricsProvider> providers = new ArrayList<>(PROVIDER_ORDER.size());
        for (String id : enabledProviderIds(order)) {
            LyricsProvider provider = providerFor(id);
            if (provider != null) {
                providers.add(provider);
            }
        }
        return providers;
    }

    /** Canonical provider ids, in the default priority order. */
    private static final List<String> PROVIDER_ORDER = Arrays.asList(
            "YTMusic", "Captions", "LRCLIB", "QQ", "NetEase", "KuGou",
            "Luna", "PetitLyrics", "bLyrics", "BiniLyrics",
            "Unison", "SimpMusic", "AMLL", "LunaBeat", "Lyricify", "Apple", "Musixmatch", "Spotify", "Deezer");

    @NonNull
    private static List<String> enabledProviderIds(String order) {
        List<String> result = new ArrayList<>();
        if (order == null || !order.contains(",")) {
            order = Settings.DEFAULT_LYRICS_ORDER;
        }
        for (String raw : order.split(",")) {
            String token = raw.trim();
            if (token.isEmpty()) {
                continue;
            }
            boolean enabled = true;
            if (token.startsWith("-")) {
                enabled = false;
                token = token.substring(1).trim();
            }
            if (!PROVIDER_ORDER.contains(token)) {
                continue;
            }
            boolean seen = false;
            for (String existing : result) {
                if (existing.equals(token)) {
                    seen = true;
                    break;
                }
            }
            if (seen) {
                continue;
            }
            if (enabled) {
                result.add(token);
            }
        }
        return result;
    }

    public static boolean isProviderEnabled(String id) {
        return enabledProviderIds(Settings.LYRICS_SOURCE.get()).contains(id);
    }

    @Nullable
    private static LyricsProvider providerFor(String id) {
        return switch (id) {
            case "YTMusic" -> new YouTubeMusicProvider();
            case "Captions" -> new CaptionsFetcher.CaptionsProvider();
            case "LRCLIB" -> new LRCLIBProvider();
            case "QQ" -> new QQProvider();
            case "NetEase" -> new NetEaseProvider();
            case "KuGou" -> new KuGouProvider();
            case "Luna" -> new LunaProvider();
            case "PetitLyrics" -> new PetitLyricsProvider();
            case "bLyrics" -> new BlyricsProvider();
            case "BiniLyrics" -> new BinimumProvider();
            case "Unison" -> new UnisonProvider();
            case "SimpMusic" -> new SimpMusicProvider();
            case "AMLL" -> new AMLLProvider();
            case "LunaBeat" -> new LunaBeatProvider();
            case "Apple" -> new AppleMusicProvider();
            case "Spotify" -> new SpotifyProvider();
            case "Lyricify" -> new LyricifyProvider();
            case "Musixmatch" -> new MusixmatchProvider();
            case "Deezer" -> new DeezerProvider();
            default -> null;
        };
    }

}
