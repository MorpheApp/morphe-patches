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
import java.util.regex.Pattern;

import app.morphe.extension.music.patches.album.PlayAlbumSongsPatch;
import app.morphe.extension.music.patches.album.PlaylistRequest;
import app.morphe.extension.music.patches.lyrics.requests.AmllProvider;
import app.morphe.extension.music.patches.lyrics.requests.AppleMusicProvider;
import app.morphe.extension.music.patches.lyrics.requests.BinimumProvider;
import app.morphe.extension.music.patches.lyrics.requests.BlyricsProvider;
import app.morphe.extension.music.patches.lyrics.requests.CaptionsFetcher;
import app.morphe.extension.music.patches.lyrics.requests.CharactersConverter;
import app.morphe.extension.music.patches.lyrics.requests.DeezerProvider;
import app.morphe.extension.music.patches.lyrics.requests.KuGouProvider;
import app.morphe.extension.music.patches.lyrics.requests.LocalLyricsFetcher;
import app.morphe.extension.music.patches.lyrics.requests.LrcLibProvider;
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
import app.morphe.extension.music.patches.lyrics.requests.YTMusicProvider;
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
            if (score != other.score) return other.score - score;
            if (rank != other.rank) return other.rank - rank;
            return 0;
        }
    }

    private static final LyricsManager INSTANCE = new LyricsManager();

    private final ExecutorService executor = Executors.newFixedThreadPool(16);

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
    private TrackInfo currentTrack;

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
    private Lyrics currentLyrics;

    private State state = State.IDLE;

    /** Temporarily disables the third-party lyrics overlay, showing native lyrics instead. */
    private boolean overrideNative;

    /**
     * Incremented for every track change so that a late response for a previous
     * track is discarded instead of being shown for the current one.
     */
    private int requestId;

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
        executor.execute(LunaBeatProvider::preloadIndex);
        executor.execute(() -> {
            MetadataCleaner.resolveSettingBlocking(Settings.LYRICS_CUSTOM_REGEX.get());
            MetadataCleaner.resolveSettingBlocking(Settings.LYRICS_TEXT_FILTER.get());
            MetadataCleaner.resolveSettingBlocking(Settings.LYRICS_CREDIT_LINE_REGEX.get());
        });
    }

    private final PriorityQueue<ScoredCandidate> candidateQueue = new PriorityQueue<>();
    private final Set<String> shownFingerprints = ConcurrentHashMap.newKeySet();
    private volatile boolean phase2Done;

    private final Map<String, Lyrics> filteredCache = Utils.createSizeRestrictedMap(32);

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

    /** Whether lyrics for the current track are loaded and ready to show. */
    public boolean hasLyrics() {
        return state == State.LOADED && currentLyrics != null && !currentLyrics.isEmpty();
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
     * Current playback position including the user configured offset.
     */
    public long getPositionMs() {
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

        return expected - Settings.LYRICS_OFFSET_MS.get() - temporaryOffsetMs;
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
    }

    /**
     * The song of an album, and the video id of the app itself, can both land after the metadata
     * of a track, so the track is read again whenever either of them arrives.
     */
    private void reloadCurrentTrack() {
        Utils.runOnMainThread(() -> {
            MediaMetadata metadata = currentMetadata;
            if (metadata != null) {
                loadTrackOf(metadata);
            }
        });
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

        if (track.title().isEmpty() || track.artist().isEmpty()) {
            return;
        }

        currentRawTitle = rawTitle;
        currentRawArtist = rawArtist;
        currentMediaUri = parseMediaUri(metadata);

        String videoId = VideoInformation.getVideoId();
        if (track.equals(currentTrack) && videoId.equals(currentVideoId)) {
            resetPosition();
            return;
        }

        currentTrack = track;
        currentVideoId = videoId;
        overrideNative = false;
        resetPosition();

        load(track);
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
    }

    public void onDisplayedTrackChanged(@Nullable String title, @Nullable String artist, @Nullable Uri mediaUri) {
        Utils.verifyOnMainThread();
        currentRawTitle = title;
        currentRawArtist = artist;
        if (title == null || title.trim().isEmpty() || artist == null || artist.trim().isEmpty()) {
            return;
        }

        String[] parsed = MetadataCleaner.parseCleanTitleAndArtist(title, artist);
        String cleanedTitle = parsed[1];
        String cleanedArtist = parsed[0];
        if (cleanedTitle.isEmpty() || cleanedArtist.isEmpty()) {
            return;
        }

        if (currentTrack != null
                && currentTrack.title().equals(cleanedTitle)
                && currentTrack.artist().equals(cleanedArtist)) {
            if (mediaUri != null) {
                currentMediaUri = mediaUri;
            }
            return;
        }

        currentTrack = new TrackInfo(cleanedTitle, cleanedArtist, "", 0);
        overrideNative = false;
        currentMediaUri = mediaUri;
        resetPosition();
        load(currentTrack);
    }

    /**
     * Temporarily disables the third-party lyrics overlay. When enabled, the native
     * lyrics panel is shown instead. Automatically cleared on track change.
     */
    public void setOverrideNative(boolean override) {
        Utils.verifyOnMainThread();
        overrideNative = override;
        if (override) {
            setState(State.IDLE, null);
        } else if (currentTrack != null) {
            load(currentTrack);
        }
    }

    public boolean isOverrideNative() {
        return overrideNative;
    }

    /**
     * Returns true for tracks backed by a local file ({@code file://} or {@code content://}) as
     * opposed to a streamed YouTube video ({@code http(s)://}). Only local files can carry
     * embedded lyrics in their tags.
     */
    private static boolean isLocalUri(@Nullable Uri uri) {
        if (uri == null) {
            return false;
        }
        String scheme = uri.getScheme();
        return "file".equals(scheme) || "content".equals(scheme);
    }

    @Nullable
    static Uri parseMediaUri(@NonNull MediaMetadata metadata) {
        String uri = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_URI);
        return (uri != null) ? Uri.parse(uri) : null;
    }

    private void load(TrackInfo track) {
        final int id = ++requestId;
        setState(State.LOADING, null);

        synchronized (candidateQueue) {
            candidateQueue.clear();
            phase2Done = false;
        }
        shownFingerprints.clear();
        filteredCache.clear();
        lastHighlightedIndex = -1;

        executor.execute(() -> runProviderLookup(id, track, null));
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

        final int id = requestId;

        setState(State.LOADING, currentLyrics);

        executor.execute(() -> {
            phase2Done = false;

            if (pollAndPublishNext(id, track)) {
                return;
            }

            if (!phase2Done) {
                String order = Settings.LYRICS_SOURCE.get();
                List<LyricsProvider> providers = providersInOrder(order);
                collectRemainingCandidates(id, track, providers);

                if (pollAndPublishNext(id, track)) {
                    return;
                }
                if (id == requestId) {
                    phase2Done = true;
                }
            }

            Utils.runOnMainThread(() -> {
                if (id != requestId) {
                    return;
                }
                setState(State.NOT_FOUND, null);
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
            if (id != requestId) {
                return false;
            }
            if (next.lyrics() != currentLyrics
                    && !shownFingerprints.contains(fingerprint(next.lyrics()))
                    && isValidLyrics(next.lyrics(), track)) {
                Utils.runOnMainThread(() -> {
                    if (id != requestId) {
                        return;
                    }
                    publish(id, next.lyrics());
                });
                return true;
            }
        }
    }

    private void collectRemainingCandidates(int id, TrackInfo track,
                                             List<LyricsProvider> providers) {
        if (id != requestId) {
            return;
        }
        Set<String> existing = new HashSet<>(shownFingerprints);
        synchronized (candidateQueue) {
            for (ScoredCandidate sc : candidateQueue) {
                existing.add(fingerprint(sc.lyrics()));
            }
        }
        if (currentLyrics != null) {
            existing.add(fingerprint(currentLyrics));
        }

        CompletionService<List<Lyrics.ScoredLyrics>> cs =
                new ExecutorCompletionService<>(executor);
        List<Future<List<Lyrics.ScoredLyrics>>> futures = new ArrayList<>();
        for (LyricsProvider provider : providers) {
            if (!provider.hasCandidates()) {
                continue;
            }
            futures.add(cs.submit(() -> {
                try {
                    return provider.fetchCandidates(track);
                } catch (Exception ex) {
                    Logger.printDebug(() -> "Phase 2 fetch failed: " + provider.name(), ex);
                    return null;
                }
            }));
        }

        int completed = 0;
        while (completed < futures.size()) {
            if (id != requestId) {
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
                    processCandidateFuture(extra, id, track, existing);
                }
                break;
            }
            completed++;
            processCandidateFuture(f, id, track, existing);
        }

        for (Future<List<Lyrics.ScoredLyrics>> f : futures) {
            if (!f.isDone()) {
                f.cancel(true);
            }
        }
    }

    private void processCandidateFuture(Future<List<Lyrics.ScoredLyrics>> f, int id,
                                        TrackInfo track, Set<String> existing) {
        if (id != requestId) {
            return;
        }
        try {
            List<Lyrics.ScoredLyrics> candidates = f.get();
            if (candidates == null) {
                return;
            }
            for (Lyrics.ScoredLyrics sc : candidates) {
                Lyrics candidate = sc.lyrics();
                if (candidate == null || !isValidLyrics(candidate, track)) {
                    continue;
                }
                String fp = fingerprint(candidate);
                synchronized (candidateQueue) {
                    if (id == requestId && existing.add(fp)) {
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

    private void runProviderLookup(int id, TrackInfo track, @Nullable TrackInfo innertubeTrack) {
        // Local files take priority: read embedded LYRICS/LYRIC tags before hitting providers.
        if (Settings.LYRICS_USE_EMBEDDED.get()) {
            Uri embeddedUri = localUriFor(track);
            if (embeddedUri != null) {
                Lyrics embedded = LocalLyricsFetcher.fetch(embeddedUri);
                if (embedded != null) {
                    LyricsCache.put(track, "LOCAL", embedded);
                    Utils.runOnMainThread(() -> publish(id, embedded));
                    return;
                }
            }
        }

        String order = Settings.LYRICS_SOURCE.get();
        List<LyricsProvider> providers = providersInOrder(order);
        if (providers.isEmpty()) {
            Utils.runOnMainThread(() -> publish(id, Lyrics.NOT_FOUND));
            return;
        }

        boolean checkInnertube = innertubeTrack != null && !innertubeTrack.equals(track);
        int notFoundCached = 0;
        for (LyricsProvider provider : providers) {
            boolean providerMissed = false;
            Lyrics cached = LyricsCache.get(track, provider.name());
            if (cached != null && cached != Lyrics.NOT_FOUND) {
                Utils.runOnMainThread(() -> publish(id, cached));
                return;
            }
            if (cached == Lyrics.NOT_FOUND) {
                providerMissed = true;
            }
            if (checkInnertube) {
                Lyrics cachedIT = LyricsCache.get(innertubeTrack, provider.name());
                if (cachedIT != null && cachedIT != Lyrics.NOT_FOUND) {
                    LyricsCache.put(track, provider.name(), cachedIT);
                    Utils.runOnMainThread(() -> publish(id, cachedIT));
                    return;
                }
                if (cachedIT == Lyrics.NOT_FOUND && cached == null) {
                    providerMissed = true;
                }
            }
            if (providerMissed) {
                notFoundCached++;
            }
        }
        if (notFoundCached >= providers.size()) {
            Utils.runOnMainThread(() -> publish(id, Lyrics.NOT_FOUND));
            return;
        }

        if (!Utils.isNetworkConnected()) {
            Utils.runOnMainThread(() -> {
                if (id == requestId) {
                    setState(State.ERROR, null);
                }
            });
            return;
        }

        boolean[] failed = {false};
        Lyrics result;

        List<VariantQuery> originalTier = new ArrayList<>();
        List<VariantQuery> derivedTier = new ArrayList<>();
        // InnerTube canonical metadata (different title/artist from localized).
        if (innertubeTrack != null && !innertubeTrack.equals(track)) {
            originalTier.add(new VariantQuery(innertubeTrack, 0));
            for (TrackInfo v : CharactersConverter.variants(innertubeTrack)) {
                derivedTier.add(new VariantQuery(v, 1));
            }
        }
        originalTier.add(new VariantQuery(track, 0));
        for (TrackInfo v : CharactersConverter.variants(track)) {
            derivedTier.add(new VariantQuery(v, 1));
        }
        String[] splitArtists = MetadataCleaner.splitArtists(track.artist());
        for (String artist : splitArtists) {
            if (!artist.equals(track.artist())) {
                derivedTier.add(new VariantQuery(new TrackInfo(
                        track.title(), artist, track.album(), track.durationSeconds()), 2));
            }
        }

        TrackInfo trusted = MetadataCleaner.trustedDashSplit(
                currentRawTitle, track.artist(), track.album(), track.durationSeconds());
        if (trusted != null && !trusted.equals(track)) {
            originalTier.add(new VariantQuery(trusted, 1));
        }

        TrackInfo dashSplit = MetadataCleaner.anyDashSplit(
                currentRawTitle, track.album(), track.durationSeconds());
        if (dashSplit != null && !dashSplit.equals(track) && !dashSplit.equals(trusted)) {
            derivedTier.add(new VariantQuery(dashSplit, 2));
        }
        TrackInfo dashSplitRev = MetadataCleaner.anyDashSplitReversed(
                currentRawTitle, track.album(), track.durationSeconds());
        if (dashSplitRev != null && !dashSplitRev.equals(track)
                && !dashSplitRev.equals(trusted) && !dashSplitRev.equals(dashSplit)) {
            derivedTier.add(new VariantQuery(dashSplitRev, 2));
        }

        LookupResult fetchResult = fetchFromProviders(originalTier, derivedTier, failed, providers);
        result = fetchResult.best;

        boolean validResult = isValidLyrics(result, track);
        if (validResult) {
            LyricsCache.put(track, result.providerName(), result);
            if (innertubeTrack != null && !innertubeTrack.equals(track)) {
                LyricsCache.put(innertubeTrack, result.providerName(), result);
            }
            Utils.runOnMainThread(() -> publish(id, result));
        }

        synchronized (candidateQueue) {
            if (id == requestId) {
                candidateQueue.clear();
                for (ScoredCandidate sc : fetchResult.scored) {
                    if (!validResult || sc.lyrics() != result) {
                        candidateQueue.add(sc);
                    }
                }
                phase2Done = false;
            }
        }

        if (id == requestId && fetchResult.queueFillPending) {
            final List<VariantQuery> fillOriginal = List.copyOf(originalTier);
            final List<VariantQuery> fillDerived = List.copyOf(derivedTier);
            final List<LyricsProvider> fillProviders = List.copyOf(providers);
            executor.execute(() -> fillQueueInBackground(id, fillOriginal, fillDerived,
                    fillProviders));
        }

        if (validResult) {
            return;
        }

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
            if (innertubeTrack != null && !innertubeTrack.equals(track)) {
                LyricsCache.put(innertubeTrack, provider.name(), Lyrics.NOT_FOUND);
            }
        }
        if (failed[0]) {
            Utils.runOnMainThread(() -> {
                if (id == requestId) {
                    setState(State.ERROR, null);
                }
            });
        } else {
            Utils.runOnMainThread(() -> publish(id, Lyrics.NOT_FOUND));
        }
    }

    private void fillQueueInBackground(int id, List<VariantQuery> originalTier,
                                       List<VariantQuery> derivedTier,
                                       List<LyricsProvider> providers) {
        if (id != requestId) {
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

        CompletionService<ProviderFetch> cs = new ExecutorCompletionService<>(executor);
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
                futures.add(cs.submit(() -> fetchOne(provider, vq, attempted, withResults,
                        failedSet, threadFailed)));
            }
        }

        long deadline = SystemClock.uptimeMillis() + 5_000;
        List<ScoredCandidate> collected = new ArrayList<>();
        DisplayState fillDisplay = new DisplayState(false);
        int completed = 0;
        while (completed < futures.size()) {
            if (id != requestId) {
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
        for (Future<ProviderFetch> f : futures) {
            if (!f.isDone()) {
                f.cancel(true);
            }
        }

        if (collected.isEmpty() || id != requestId) {
            return;
        }
        Set<String> existing = new HashSet<>(shownFingerprints);
        synchronized (candidateQueue) {
            if (id != requestId) {
                return;
            }
            for (ScoredCandidate sc : candidateQueue) {
                existing.add(fingerprint(sc.lyrics()));
            }
            if (currentLyrics != null) {
                existing.add(fingerprint(currentLyrics));
            }
            for (ScoredCandidate sc : collected) {
                if (id == requestId && existing.add(fingerprint(sc.lyrics()))) {
                    candidateQueue.add(sc);
                }
            }
        }
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

    private record LookupResult(@Nullable Lyrics best,
                                List<ScoredCandidate> scored,
                                Set<String> providersWithResults,
                                Set<String> providersFailed,
                                Set<String> providersAttempted,
                                boolean queueFillPending) {}

    @Nullable
    private LookupResult fetchFromProviders(List<VariantQuery> originalTier,
                                            List<VariantQuery> derivedTier,
                                            boolean[] failed,
                                            List<LyricsProvider> providers) {
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

        CompletionService<ProviderFetch> cs = new ExecutorCompletionService<>(executor);
        List<Future<ProviderFetch>> futures = new ArrayList<>();
        Set<String> providersWithResults = ConcurrentHashMap.newKeySet();
        Set<String> providersFailed = ConcurrentHashMap.newKeySet();
        Set<String> providersAttempted = ConcurrentHashMap.newKeySet();
        AtomicBoolean threadFailed = new AtomicBoolean(false);

        List<ScoredCandidate> scoreCandidates = new ArrayList<>();
        final DisplayState display = new DisplayState(wordSync);

        for (VariantQuery vq : originalTier) {
            for (LyricsProvider provider : stage1Providers) {
                futures.add(cs.submit(() -> fetchOne(provider, vq, providersAttempted,
                        providersWithResults, providersFailed, threadFailed)));
            }
        }

        int completed = 0;
        boolean stage2Submitted = false;
        boolean stage3Submitted = false;
        long endgameAt = start + 5_000;

        completed = pollStage(cs, futures, completed, stage1Deadline, display, scoreCandidates,
                endgameAt, true);

        if (!display.windowClosed && !stage2Providers.isEmpty()) {
            stage2Submitted = true;
            for (VariantQuery vq : originalTier) {
                for (LyricsProvider provider : stage2Providers) {
                    futures.add(cs.submit(() -> fetchOne(provider, vq, providersAttempted,
                            providersWithResults, providersFailed, threadFailed)));
                }
            }
            completed = pollStage(cs, futures, completed, stage2Deadline, display,
                    scoreCandidates, endgameAt, true);
        }

        if (!display.windowClosed) {
            stage3Submitted = true;
            for (VariantQuery vq : derivedTier) {
                for (LyricsProvider provider : nonBlind) {
                    futures.add(cs.submit(() -> fetchOne(provider, vq, providersAttempted,
                            providersWithResults, providersFailed, threadFailed)));
                }
                for (LyricsProvider provider : blind) {
                    futures.add(cs.submit(() -> fetchOne(provider, vq, providersAttempted,
                            providersWithResults, providersFailed, threadFailed)));
                }
            }
            for (VariantQuery vq : originalTier) {
                for (LyricsProvider provider : blind) {
                    futures.add(cs.submit(() -> fetchOne(provider, vq, providersAttempted,
                            providersWithResults, providersFailed, threadFailed)));
                }
            }
            completed = pollStage(cs, futures, completed, stage3Deadline, display,
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
        for (Future<ProviderFetch> f : futures) {
            if (!f.isDone()) {
                f.cancel(true);
            }
        }

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

    private int pollStage(CompletionService<ProviderFetch> cs,
                          List<Future<ProviderFetch>> futures,
                          int completed,
                          long deadline,
                          DisplayState display,
                          List<ScoredCandidate> scoreCandidates,
                          long endgameAt,
                          boolean stage12) {
        while (completed < futures.size()) {
            long now = SystemClock.uptimeMillis();
            if (now >= endgameAt && display.bestResult == null && display.bestFallback != null) {
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
                f = cs.poll(wait, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ex) {
                Logger.printDebug(() -> "Interrupted polling provider futures", ex);
                Thread.currentThread().interrupt();
                break;
            }
            if (f == null) {
                if (now >= endgameAt && display.bestResult == null) {
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
                if (display.accept(f.get(), scoreCandidates, stage12)) {
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
        @Nullable Lyrics bestResult;
        int bestDisplayRank;
        @Nullable Lyrics bestFallback;
        int bestFallbackRank;
        boolean windowClosed;

        DisplayState(boolean wordSync) {
            this.wordSync = wordSync;
            this.bestDisplayRank = wordSync ? -1 : -2;
            // Plain (rank 0) high-match must qualify as endgame fallback; default 0 would not.
            this.bestFallbackRank = -1;
        }

        boolean accept(@Nullable ProviderFetch pf, List<ScoredCandidate> scoreCandidates,
                       boolean stage12) {
            if (pf == null) {
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
                    return true;
                }
                return false;
            }

            final int effectiveRank = rank == 2 ? -1 : rank;
            if (effectiveRank > bestDisplayRank) {
                bestResult = fetched;
                bestDisplayRank = effectiveRank;
            }
            return stage12 && effectiveRank >= 1 && bestResult != null;
        }
    }

    private static boolean isBlindProvider(LyricsProvider provider) {
        return switch (provider.name()) {
            case "Spotify", "AMLL", "bLyrics", "BiniLyrics", "Lyricify" -> true;
            default -> false;
        };
    }

    private record ProviderFetch(@Nullable Lyrics lyrics, int matchScore, boolean highMatch,
                                 int penalty) {}

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
                    vq.penalty());
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

    private void publish(int id, Lyrics lyrics) {
        if (id != requestId) {
            return;
        }

        String incomingFp = null;
        if (lyrics != null && lyrics != Lyrics.NOT_FOUND && !lyrics.isEmpty()) {
            incomingFp = fingerprint(lyrics);
        }

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
        if (lyrics.synced() && !lyrics.isEmpty()) {
            lyrics = new Lyrics(
                    Lyrics.clampLastWordEnds(
                            Lyrics.fixAnomalousWordTimestamps(lyrics.lines())),
                    lyrics.providerName(), true, lyrics.romanization(),
                    lyrics.translations(), lyrics.romanizations(),
                    lyrics.songwriters(), lyrics.rawFormat(),
                    lyrics.formatType(), lyrics.sourceUrl());
        }

        if (lyrics == Lyrics.NOT_FOUND || lyrics.isEmpty()) {
            setState(State.NOT_FOUND, null);
        } else {
            if (incomingFp != null) {
                shownFingerprints.add(incomingFp);
            }
            shownFingerprints.add(fingerprint(lyrics));
            setState(State.LOADED, lyrics);
            LyricsPanelInstaller.enableLyricsButton();
            Utils.runOnMainThreadDelayed(LyricsPanelInstaller::onLyricsPanelDetected, 300);
        }
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

        boolean hasRealSeparator = false;
        if (sepIdx >= 0) {
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
            hasRealSeparator = realSepIdx >= 0
                    && (spaceSepIdx < 0 || realSepIdx < spaceSepIdx);
        }

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

    private static int countSlashes(String text) {
        int count = 0;
        for (int i = 0, length = text.length(); i < length; i++) {
            if (text.charAt(i) == '/') {
                count++;
            }
        }
        return count;
    }

    /**
     * Applies {@link Settings#LYRICS_TEXT_FILTER} to the original lyrics text only.
     * Translations and romanizations are left untouched.
     */
    private static Lyrics filterLyricsText(Lyrics lyrics) {
        if (lyrics == Lyrics.NOT_FOUND) {
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
            String text = MetadataCleaner.applyRegex(line.text(), filter);
            if (text.isEmpty()) {
                anyDropped = true;
                continue;
            }
            if (line.hasWords()) {
                List<Word> words = new ArrayList<>(line.words().size());
                for (Word w : line.words()) {
                    String wt = MetadataCleaner.applyRegex(w.text(), filter);
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

    private void setState(State newState, @Nullable Lyrics lyrics) {
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
        if (currentLyrics == null || !currentLyrics.synced() || currentLyrics.isEmpty()) {
            return "";
        }
        final int index = currentLyrics.indexForPosition(getPositionMs(), lastHighlightedIndex);
        lastHighlightedIndex = index;
        if (index < 0) {
            return "";
        }
        String text = currentLyrics.lines().get(index).text();
        return text == null ? "" : text;
    }

    public boolean areLyricsSynced() {
        return currentLyrics != null && currentLyrics.synced() && !currentLyrics.isEmpty();
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

    @Nullable
    private static LyricsProvider providerFor(String id) {
        return switch (id) {
            case "YTMusic" -> new YTMusicProvider();
            case "Captions" -> new CaptionsFetcher.CaptionsProvider();
            case "LRCLIB" -> new LrcLibProvider();
            case "QQ" -> new QQProvider();
            case "NetEase" -> new NetEaseProvider();
            case "KuGou" -> new KuGouProvider();
            case "Luna" -> new LunaProvider();
            case "PetitLyrics" -> new PetitLyricsProvider();
            case "bLyrics" -> new BlyricsProvider();
            case "BiniLyrics" -> new BinimumProvider();
            case "Unison" -> new UnisonProvider();
            case "SimpMusic" -> new SimpMusicProvider();
            case "AMLL" -> new AmllProvider();
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
