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
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import app.morphe.extension.music.patches.album.PlayAlbumSongsPatch;
import app.morphe.extension.music.patches.album.PlaylistRequest;
import app.morphe.extension.music.patches.lyrics.requests.CharactersConverter;
import app.morphe.extension.music.patches.lyrics.requests.CaptionsFetcher;
import app.morphe.extension.music.patches.lyrics.requests.KuGouProvider;
import app.morphe.extension.music.patches.lyrics.requests.LocalLyricsFetcher;
import app.morphe.extension.music.patches.lyrics.requests.LrcLibProvider;
import app.morphe.extension.music.patches.lyrics.requests.LyricallyAppleMusicProvider;
import app.morphe.extension.music.patches.lyrics.requests.LunaProvider;
import app.morphe.extension.music.patches.lyrics.requests.LyricsProvider;
import app.morphe.extension.music.patches.lyrics.requests.NetEaseProvider;
import app.morphe.extension.music.patches.lyrics.requests.QQProvider;
import app.morphe.extension.music.patches.lyrics.requests.BinimumProvider;
import app.morphe.extension.music.patches.lyrics.requests.BlyricsProvider;
import app.morphe.extension.music.patches.lyrics.requests.MusixmatchProvider;
import app.morphe.extension.music.patches.lyrics.requests.UnisonProvider;
import app.morphe.extension.music.patches.lyrics.requests.AmllProvider;
import app.morphe.extension.music.patches.lyrics.requests.AppleMusicProvider;
import app.morphe.extension.music.patches.lyrics.requests.DeezerProvider;
import app.morphe.extension.music.patches.lyrics.requests.SpotifyProvider;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.music.shared.VideoInformation;
import app.morphe.extension.shared.Utils;

/**
 * Fetches lyrics for the currently playing track and tracks playback position.
 *
 * <p>The position is extrapolated from the last {@link PlaybackState} update so synced
 * lyrics stay accurate to a few tens of milliseconds between updates, but the
 * extrapolation is re-anchored to the player time hook ({@link VideoInformation#getVideoTime()},
 * which ticks roughly once per second) so any drift between the two clocks cannot
 * accumulate. A seek or play/pause also re-anchors via {@link #onSetPlaybackState}.
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

    private static final LyricsManager INSTANCE = new LyricsManager();

    private final ExecutorService executor = Executors.newFixedThreadPool(4);

    private final List<Listener> listeners = new ArrayList<>(2);

    private static final Lyrics EMPTY_CAPTIONS =
            new Lyrics(Collections.emptyList(), Lyrics.CAPTIONS_PROVIDER, true);

    private static final int MAX_ARTIST_SONG_LINE_LENGTH = 80;

    private static final Pattern ARTIST_SONG_PATTERN =
            Pattern.compile("^[^\\-]+\\s*-\\s*[^\\-]+$");

    private static final int CREDIT_LINE_GAP_TOLERANCE = 3;

    @Nullable
    private TrackInfo currentTrack;

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
    private float playbackSpeed = 1f;
    private boolean playing;

    private long smoothedPosition = -1;

    private LyricsManager() {
        PlayAlbumSongsPatch.addSubstitutionListener(
                (videoId, resolvedVideoId) -> reloadCurrentTrack());
        VideoInformation.addVideoIdListener(videoId -> reloadCurrentTrack());
    }

    private int currentProviderIndex;
    private int currentCandidateIndex;
    private List<LyricsProvider> currentProviders;
    private int currentCandidateRequestId;
    private java.util.Map<Integer, List<Lyrics>> candidateCache;

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
        smoothedPosition = -1;
    }

    /**
     * Current playback position including the user configured offset.
     */
    public long getPositionMs() {
        final long videoTime = VideoInformation.getVideoTime();
        if (videoTime > 0 && videoTime != lastVideoTimeSample) {
            positionMs = videoTime;
            positionUpdatedAtUptimeMs = SystemClock.uptimeMillis();
            lastVideoTimeSample = videoTime;
        }

        long position = positionMs;
        if (playing && positionUpdatedAtUptimeMs != 0) {
            final long elapsed = SystemClock.uptimeMillis() - positionUpdatedAtUptimeMs;
            position += (long) (elapsed * playbackSpeed);
        }
        long result = position - Settings.LYRICS_OFFSET_MS.get();

        // Position smoothing: reject implausible forward jumps.
        // Normal 120ms tick advances ~120ms at 1x speed.
        // Backward jumps are real seeks — accept immediately.
        // Reject forward jumps > 60s (likely garbage, real seeks ≤ 60s).
        if (result > 0 && smoothedPosition >= 0) {
            final long delta = result - smoothedPosition;
            if (delta < 0) {
                smoothedPosition = result;
            } else if (delta > 60_000) {
                return smoothedPosition;
            }
        }
        if (result > 0) {
            smoothedPosition = result;
        }
        return smoothedPosition >= 0 ? smoothedPosition : result;
    }

    /**
     * Injection point relay. Called on the main thread.
     */
    public void onSetMetadata(@Nullable MediaMetadata metadata) {
        Utils.verifyOnMainThread();
        if (metadata == null) {
            return;
        }
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
        if (rawTitle == null || rawTitle.isBlank() || rawArtist == null || rawArtist.isBlank()) {
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

        String[] parsed = MetadataCleaner.parseTitleAndArtist(rawTitle);
        String effectiveTitle = parsed != null ? parsed[1] : MetadataCleaner.cleanTitle(rawTitle);
        String effectiveArtist = parsed != null ? parsed[0] : MetadataCleaner.cleanArtist(rawArtist);

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

        if (track.equals(currentTrack)) {
            resetPosition();
            return;
        }

        currentTrack = track;
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
        final long newPosition = playbackState.getPosition();

        if (smoothedPosition >= 0 && positionMs != newPosition) {
            final long expected = positionMs
                    + (long) ((SystemClock.uptimeMillis() - positionUpdatedAtUptimeMs) * playbackSpeed);
            if (Math.abs(newPosition - expected) > 2000) {
                smoothedPosition = -1;
            }
        }

        positionMs = newPosition;
        positionUpdatedAtUptimeMs = SystemClock.uptimeMillis();

        final float speed = playbackState.getPlaybackSpeed();
        // A paused state reports a speed of zero, which would freeze extrapolation
        // even after playback resumes, so only positive speeds are kept.
        if (speed > 0) {
            playbackSpeed = speed;
        }
    }

    public void onDisplayedTrackChanged(@Nullable String title, @Nullable String artist) {
        onDisplayedTrackChanged(title, artist, null);
    }

    public void onDisplayedTrackChanged(@Nullable String title, @Nullable String artist, @Nullable Uri mediaUri) {
        Utils.verifyOnMainThread();
        currentRawTitle = title;
        currentRawArtist = artist;
        if (title == null || title.isBlank() || artist == null || artist.isBlank()) {
            return;
        }

        final String[] parsed = MetadataCleaner.parseTitleAndArtist(title);
        final String cleanedTitle = parsed != null ? parsed[1] : MetadataCleaner.cleanTitle(title);
        final String cleanedArtist = parsed != null ? parsed[0] : MetadataCleaner.cleanArtist(artist);
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

    public void clearLyrics() {
        Utils.verifyOnMainThread();
        currentMediaUri = null;
        setState(State.IDLE, null);
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
        final String scheme = uri.getScheme();
        return "file".equals(scheme) || "content".equals(scheme);
    }

    @Nullable
    private static Uri parseMediaUri(@NonNull MediaMetadata metadata) {
        final String uri = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_URI);
        return (uri != null) ? Uri.parse(uri) : null;
    }

    private void load(TrackInfo track) {
        final int id = ++requestId;
        setState(State.LOADING, null);

        currentProviderIndex = 0;
        currentCandidateIndex = 0;
        currentProviders = null;
        currentCandidateRequestId = id;
        candidateCache = null;

        executor.execute(() -> {
            runProviderLookup(id, track, null);
        });
    }

    /**
     * Fetches the next candidate lyrics. Called from the refresh button.
     * Cycles through candidates within the current source first, then falls back
     * to the next source. Circular cycling: all sources exhausted → cycle back to first.
     */
    public void fetchNextCandidate() {
        Utils.verifyOnMainThread();
        final TrackInfo track = currentTrack;
        if (track == null) {
            return;
        }

        final int id = currentCandidateRequestId;
        if (id != requestId) {
            return;
        }

        if (currentProviders == null) {
            final String order = Settings.LYRICS_SOURCE.get();
            currentProviders = new ArrayList<>(providersInOrder(order));
            candidateCache = new java.util.HashMap<>();
        }

        setState(State.LOADING, null);

        executor.execute(() -> {
            final int totalProviders = currentProviders.size();

            for (int attempt = 0; attempt < totalProviders; attempt++) {
                final LyricsProvider provider = currentProviders.get(currentProviderIndex);

                List<Lyrics> candidates = candidateCache.get(currentProviderIndex);
                if (candidates == null && provider.hasCandidates()) {
                    try {
                        candidates = provider.fetchCandidates(track);
                        if (candidates != null && candidates.size() > 5) {
                            candidates = new ArrayList<>(candidates.subList(0, 5));
                        }
                    } catch (Exception ex) {
                    }
                    if (candidates == null) {
                        candidates = new ArrayList<>();
                    }
                    candidateCache.put(currentProviderIndex, candidates);
                }

                if (candidates != null && !candidates.isEmpty()
                        && currentCandidateIndex < candidates.size()) {
                    final Lyrics candidate = candidates.get(currentCandidateIndex);
                    if (isValidLyrics(candidate, track)) {
                        final Lyrics toPublish = candidate;
                        Utils.runOnMainThread(() -> {
                            if (id != requestId) {
                                return;
                            }
                            publish(id, toPublish);
                        });
                        currentCandidateIndex++;
                        return;
                    }
                }

                currentProviderIndex = (currentProviderIndex + 1) % totalProviders;
                currentCandidateIndex = 0;
            }

            Utils.runOnMainThread(() -> {
                if (id != requestId) {
                    return;
                }
                load(track);
            });
        });
    }

    private void runProviderLookup(int id, TrackInfo track, @Nullable TrackInfo innertubeTrack) {
        // Local files take priority: read embedded LYRICS/LYRIC tags before hitting providers.
        if (Settings.LYRICS_USE_EMBEDDED.get()) {
            final Uri embeddedUri = localUriFor(track);
            if (embeddedUri != null) {
                final Lyrics embedded = LocalLyricsFetcher.fetch(embeddedUri);
                if (embedded != null) {
                    LyricsCache.put(track, LyricsSource.LOCAL.name(), embedded);
                    final Lyrics toPublish = embedded;
                    Utils.runOnMainThread(() -> publish(id, toPublish));
                    return;
                }
            }
        }

        final String order = Settings.LYRICS_SOURCE.get();
        final List<LyricsProvider> providers = providersInOrder(order);

        // A cached hit for any provider short-circuits the lookup. A cached miss is
        // ignored so a lower-priority provider with cached lyrics still gets a chance.
        // Check both localized and InnerTube metadata caches.
        for (LyricsProvider provider : providers) {
            final Lyrics cached = LyricsCache.get(track, provider.name());
            if (cached != null && cached != Lyrics.NOT_FOUND) {
                final Lyrics toPublish = cached;
                Utils.runOnMainThread(() -> publish(id, toPublish));
                return;
            }
            if (innertubeTrack != null && !innertubeTrack.equals(track)) {
                final Lyrics cachedIT = LyricsCache.get(innertubeTrack, provider.name());
                if (cachedIT != null && cachedIT != Lyrics.NOT_FOUND) {
                    final Lyrics toPublish = cachedIT;
                    LyricsCache.put(track, provider.name(), toPublish);
                    Utils.runOnMainThread(() -> publish(id, toPublish));
                    return;
                }
            }
        }

        if (!Utils.isNetworkConnected()) {
            Utils.runOnMainThread(() -> {
                if (id == requestId) {
                    setState(State.ERROR, null);
                }
            });
            return;
        }

        final boolean[] failed = {false};
        Lyrics result = null;

        List<TrackInfo> variants = new ArrayList<>();
        // InnerTube canonical metadata (different title/artist from localized).
        if (innertubeTrack != null && !innertubeTrack.equals(track)) {
            // Check cache for InnerTube metadata too.
            for (LyricsProvider provider : providers) {
                final Lyrics cached = LyricsCache.get(innertubeTrack, provider.name());
                if (cached != null && cached != Lyrics.NOT_FOUND) {
                    final Lyrics toPublish = cached;
                    LyricsCache.put(track, provider.name(), toPublish);
                    Utils.runOnMainThread(() -> publish(id, toPublish));
                    return;
                }
            }
            variants.add(innertubeTrack);
            variants.addAll(CharactersConverter.variants(innertubeTrack));
        }
        variants.add(track);
        variants.addAll(CharactersConverter.variants(track));
        final String[] splitArtists = MetadataCleaner.splitArtists(track.artist());
        for (String artist : splitArtists) {
            if (!artist.equals(track.artist())) {
                variants.add(new TrackInfo(
                        track.title(), artist, track.album(), track.durationSeconds()));
            }
        }
        final TrackInfo swapped = MetadataCleaner.swapTitleAndArtist(track, currentRawTitle);
        if (swapped != null) {
            variants.add(swapped);
        }

        result = fetchFromProviders(variants, failed, providers);

        if (isValidLyrics(result, track)) {
            final Lyrics toPublish = result;
            LyricsCache.put(track, result.providerName(), toPublish);
            if (innertubeTrack != null && !innertubeTrack.equals(track)) {
                LyricsCache.put(innertubeTrack, result.providerName(), toPublish);
            }
            Utils.runOnMainThread(() -> publish(id, toPublish));
            return;
        }

        // No provider returned lyrics: remember the miss for every enabled provider.
        for (LyricsProvider provider : providers) {
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

    /**
     * A track is local (not a streamed YouTube video) when the media session already exposes a
     * local file/content URI, or when no video id is known. Streamed videos always carry a video
     * id, so an empty id is the reliable "local song" signal used elsewhere (subtitles, Unison).
     */
    private boolean isLocalTrack() {
        if (isLocalUri(currentMediaUri)) {
            return true;
        }
        // The video id is set on the main thread and may not have settled yet when this runs on the
        // executor. Wait briefly so a streamed video's id can appear (proving it is not local) and
        // so a local song (id stays empty) is not mistaken for a video. Mirrors CaptionsFetcher.
        for (int i = 0; i < 3; i++) {
            if (VideoInformation.getVideoId().isEmpty()) {
                return true;
            }
            if (i < 2) {
                try {
                    Thread.sleep(150);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return false;
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
        return LocalLyricsFetcher.resolveMediaStoreUri(
                track.title(), track.artist(), track.durationSeconds(), currentRawTitle, currentRawArtist);
    }

    @Nullable
    private Lyrics fetchFromProviders(TrackInfo track, boolean[] failed, List<LyricsProvider> providers) {
        final CompletionService<Lyrics> cs = new ExecutorCompletionService<>(executor);
        final List<Future<Lyrics>> futures = new ArrayList<>(providers.size());
        final AtomicBoolean threadFailed = new AtomicBoolean(false);

        for (LyricsProvider provider : providers) {
            futures.add(cs.submit(() -> {
                try {
                    return provider.fetch(track);
                } catch (Exception ex) {
                    threadFailed.set(true);
                    return null;
                }
            }));
        }

        final boolean wordSync = Settings.LYRICS_WORD_SYNC.get();
        Lyrics bestResult = null;
        int bestRank = wordSync ? -1 : -2;
        int completed = 0;
        long deadline = System.currentTimeMillis() + 15_000;

        while (completed < futures.size()) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                break;
            }

            Future<Lyrics> f;
            try {
                f = cs.poll(remaining, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            if (f == null) {
                break;
            }

            completed++;
            try {
                final Lyrics fetched = f.get();
                if (!isValidLyrics(fetched, track)) {
                    continue;
                }
                final int rank = rankOf(fetched);
                if (wordSync) {
                    if (rank > bestRank) {
                        bestRank = rank;
                        bestResult = fetched;
                    }
                    if (rank == 2) {
                        break; // word-synced is the best possible tier
                    }
                } else {
                    final int effectiveRank = rank == 2 ? -1 : rank;
                    if (effectiveRank > bestRank) {
                        bestRank = effectiveRank;
                        bestResult = fetched;
                    }
                    if (effectiveRank == 1) {
                        break; // line-synced is the best preferred tier
                    }
                }
            } catch (Exception e) {
            }
        }

        for (Future<Lyrics> f : futures) {
            if (!f.isDone()) {
                f.cancel(true);
            }
        }

        failed[0] = threadFailed.get();
        return bestResult;
    }

    @Nullable
    private Lyrics fetchFromProviders(List<TrackInfo> variants,
                                      boolean[] failed,
                                      List<LyricsProvider> providers) {
        final CompletionService<Lyrics> cs = new ExecutorCompletionService<>(executor);
        final List<Future<Lyrics>> futures = new ArrayList<>(variants.size() * providers.size());
        final AtomicBoolean threadFailed = new AtomicBoolean(false);

        for (TrackInfo track : variants) {
            for (LyricsProvider provider : providers) {
                futures.add(cs.submit(() -> {
                    try {
                        return provider.fetch(track);
                    } catch (Exception ex) {
                        threadFailed.set(true);
                        return null;
                    }
                }));
            }
        }

        final boolean wordSync = Settings.LYRICS_WORD_SYNC.get();
        Lyrics bestResult = null;
        int bestRank = wordSync ? -1 : -2;
        int completed = 0;
        long deadline = System.currentTimeMillis() + 8_000;

        while (completed < futures.size()) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                break;
            }

            Future<Lyrics> f;
            try {
                f = cs.poll(remaining, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            if (f == null) {
                break;
            }

            completed++;
            try {
                final Lyrics fetched = f.get();
                if (!isValidLyrics(fetched, null)) {
                    continue;
                }
                final int rank = rankOf(fetched);
                if (wordSync) {
                    if (rank > bestRank) {
                        bestRank = rank;
                        bestResult = fetched;
                    }
                    if (rank == 2) {
                        break;
                    }
                } else {
                    final int effectiveRank = rank == 2 ? -1 : rank;
                    if (effectiveRank > bestRank) {
                        bestRank = effectiveRank;
                        bestResult = fetched;
                    }
                    if (effectiveRank == 1) {
                        break;
                    }
                }
            } catch (Exception e) {
            }
        }

        for (Future<Lyrics> f : futures) {
            if (!f.isDone()) {
                f.cancel(true);
            }
        }

        failed[0] = threadFailed.get();
        return bestResult;
    }

    private static int rankOf(Lyrics lyrics) {
        for (LyricsLine line : lyrics.lines()) {
            if (line.hasWords()) {
                return 2;
            }
        }
        return lyrics.synced() ? 1 : 0;
    }

    private void publish(int id, Lyrics lyrics) {
        if (id != requestId) {
            return;
        }

        lyrics = filterCreditLines(lyrics, currentTrack);
        lyrics = filterLyricsText(lyrics);
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
            setState(State.LOADED, lyrics);
            LyricsPanelInstaller.enableLyricsButton();
            Utils.runOnMainThreadDelayed(() -> LyricsPanelInstaller.onLyricsPanelDetected(), 300);
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

        // Second pass: only filter credit lines at start/end boundary blocks
        boolean[] kept = new boolean[size];
        List<String> creditLines = new ArrayList<>();
        List<LyricsLine> filteredLines = new ArrayList<>(size);

        for (int i = 0; i < size; i++) {
            LyricsLine line = original.get(i);
            String text = line.text().trim();
            boolean inStartBlock = i <= startBlockEnd;
            boolean inEndBlock = i >= endBlockStart;
            boolean shouldFilter = inStartBlock || inEndBlock;

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
        Map<String, List<LyricsLine>> result = new HashMap<>(map.size());
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
        if (countChar(text, '/') > 5) {
            return true;
        }
        String setting = Settings.LYRICS_CREDIT_LINE_REGEX.get();
        if (setting.isBlank()) {
            return false;
        }

        String normalized = CharactersConverter.normalize(text);

        String[] markers = setting.split(",");
        Set<String> allVariantsSet = new LinkedHashSet<>();
        for (String marker : markers) {
            marker = marker.trim();
            if (marker.isEmpty()) {
                continue;
            }
            allVariantsSet.addAll(CharactersConverter.variants(marker));
        }
        List<String> allVariants = new ArrayList<>(allVariantsSet);

        for (String variant : allVariants) {
            if (variant.length() >= 2 && normalized.startsWith(variant)) {
                int end = variant.length();
                if (end >= normalized.length()
                        || isCreditLabelBoundary(normalized, end, allVariants, variant)) {
                    return true;
                }
            }
        }

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

        for (String variant : allVariants) {
            if (variant.isEmpty()) {
                continue;
            }
            if (beforeSep.equals(variant)) {
                return true;
            }
            if (sepIdx >= 0 && beforeSep.endsWith(variant)) {
                int startIdx = beforeSep.length() - variant.length();
                if (startIdx > 0) {
                    char prev = beforeSep.charAt(startIdx - 1);
                    if (!Character.isLetterOrDigit(prev)) {
                        return true;
                    }
                }
            }
            if (variant.length() >= 2 && isCjk(variant.charAt(0))
                    && beforeSep.startsWith(variant)) {
                int end = variant.length();
                if (end >= beforeSep.length() || isCreditLabelBoundary(beforeSep, end, allVariants, variant)) {
                    return true;
                }
            }
        }
        return false;
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
            if (pos > 0 && (isCjk(text.charAt(pos - 1))
                    || currentVariant.indexOf(' ') >= 0)) {
                return true;
            }
        }
        if (c == '和' || c == '与' || c == '及') {
            return true;
        }
        if (pos > 0) {
            char prev = text.charAt(pos - 1);
            if (isCjk(prev) != isCjk(c) && Character.isLetterOrDigit(c)) {
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

    private static boolean isCjk(char c) {
        return Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN;
    }

    private static boolean isArtistSongLine(String text, TrackInfo track) {
        if (track == null) {
            return false;
        }
        String artist = track.artist();
        String title = track.title();
        if (artist == null || artist.isBlank() || title == null || title.isBlank()) {
            return false;
        }
        int dashIdx = text.indexOf('-');
        if (dashIdx <= 0 || dashIdx >= text.length() - 1) {
            return false;
        }
        List<String> leftVariants = CharactersConverter.variants(text.substring(0, dashIdx).trim());
        List<String> rightVariants = CharactersConverter.variants(text.substring(dashIdx + 1).trim());
        List<String> artistVariants = CharactersConverter.variants(artist.trim());
        List<String> titleVariants = CharactersConverter.variants(title.trim());
        if (artistVariants.isEmpty() || titleVariants.isEmpty()) {
            return false;
        }
        boolean leftMatchesArtist = containsAnyVariant(leftVariants, artistVariants);
        boolean rightMatchesTitle = containsAnyVariant(rightVariants, titleVariants);
        boolean leftMatchesTitle = containsAnyVariant(leftVariants, titleVariants);
        boolean rightMatchesArtist = containsAnyVariant(rightVariants, artistVariants);
        return (leftMatchesArtist && rightMatchesTitle) || (leftMatchesTitle && rightMatchesArtist);
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

    private static int countChar(String text, char c) {
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == c) {
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
        String filter = Settings.LYRICS_TEXT_FILTER.get();
        if (filter.isBlank()) {
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
            }
        }

        if (newState == State.LOADED) {
            // A panel is only detected while the app builds its own lyrics into it, which it
            // never does for a music video, so an open panel is covered from here instead.
            LyricsPanelInstaller.onLyricsPanelDetected();
        }
    }

    @NonNull
    public String getCurrentLineText() {
        if (currentLyrics == null || !currentLyrics.synced() || currentLyrics.isEmpty()) {
            return "";
        }
        final int index = currentLyrics.indexForPosition(getPositionMs(), -1);
        if (index < 0) {
            return "";
        }
        final String text = currentLyrics.lines().get(index).text();
        return text == null ? "" : text;
    }

    public boolean areLyricsAvailable() {
        return currentLyrics != null
                && currentLyrics != Lyrics.NOT_FOUND
                && !currentLyrics.isEmpty();
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
            "Captions", "LRCLIB", "LyricallyApple", "QQ", "NetEase", "KuGou",
            "Luna", "bLyrics", "BiniLyrics",
            "Unison", "AMLL", "Apple", "Musixmatch", "Spotify", "Deezer");

    @NonNull
    private static List<String> enabledProviderIds(String order) {
        List<String> result = new ArrayList<>();
        if (order == null || order.isEmpty() || !order.contains(",")) {
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
        if (result.isEmpty()) {
            result.addAll(PROVIDER_ORDER);
        }
        return result;
    }

    @Nullable
    private static LyricsProvider providerFor(String id) {
        switch (id) {
            case "Captions": return new CaptionsFetcher.CaptionsProvider();
            case "LRCLIB": return new LrcLibProvider();
            case "LyricallyApple": return new LyricallyAppleMusicProvider();
            case "Spotify": return new SpotifyProvider();
            case "QQ": return new QQProvider();
            case "KuGou": return new KuGouProvider();
            case "Luna": return new LunaProvider();
            case "NetEase": return new NetEaseProvider();
            case "BiniLyrics": return new BinimumProvider();
            case "bLyrics": return new BlyricsProvider();
            case "Musixmatch": return new MusixmatchProvider();
            case "Unison": return new UnisonProvider();
            case "AMLL": return new AmllProvider();
            case "Apple": return new AppleMusicProvider();
            case "Deezer": return new DeezerProvider();
            default: return null;
        }
    }

    /**
     * Maps a lyrics (content) timeline position to the player video time.
     */
    public long toVideoTime(long contentMs) {
        return contentMs;
    }
}
