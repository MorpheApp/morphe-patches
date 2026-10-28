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
import android.os.Looper;
import android.os.SystemClock;
import android.view.Choreographer;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
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

import app.morphe.extension.music.patches.lyrics.derive.SectionDetector;
import app.morphe.extension.music.patches.lyrics.model.Lyrics;
import app.morphe.extension.music.patches.lyrics.model.LyricsFilter;
import app.morphe.extension.music.patches.lyrics.model.LyricsFilterDefaults;
import app.morphe.extension.music.patches.lyrics.model.LyricsLine;
import app.morphe.extension.music.patches.lyrics.model.LyricsPreference;
import app.morphe.extension.music.patches.lyrics.model.MetadataCleaner;
import app.morphe.extension.music.patches.lyrics.model.MiniLyricsFormatter;
import app.morphe.extension.music.patches.lyrics.model.TrackInfo;
import app.morphe.extension.music.patches.lyrics.model.TrackVersion;
import app.morphe.extension.music.patches.lyrics.parsers.CharactersConverter;
import app.morphe.extension.music.patches.lyrics.requests.DeezerProvider;
import app.morphe.extension.music.patches.lyrics.requests.LocalLyricsFetcher;
import app.morphe.extension.music.patches.lyrics.requests.LunaBeatProvider;
import app.morphe.extension.music.patches.lyrics.requests.LyricsProvider;
import app.morphe.extension.music.patches.lyrics.requests.LyricsRequests;
import app.morphe.extension.music.patches.lyrics.storage.LyricsCache;
import app.morphe.extension.music.patches.lyrics.storage.TrackOffsetStore;
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

    record ScoredCandidate(int score, int rank, Lyrics lyrics)
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

    private static final long REBINDING_CONFIRM_MS = 1500;

    private final ExecutorService lookupExecutor = Executors.newFixedThreadPool(8);

    private final ExecutorService fetchExecutor = Executors.newFixedThreadPool(16);

    private final Set<Future<?>> activeFetches = ConcurrentHashMap.newKeySet();

    private <T> Future<T> submitFetch(int id, CompletionService<T> service,
                                      java.util.concurrent.Callable<T> fetch) {
        Future<T> future = service.submit(() -> id == requestId.get() ? fetch.call() : null);
        activeFetches.add(future);
        // The track can change between submission and registration.
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

    @Nullable
    private volatile TrackInfo currentTrack;

    @Nullable
    private volatile String sectionsPendingFingerprint;

    @NonNull
    private String currentVideoId = "";

    /** Metadata of the current track, kept so it can be read again as the song of an album. */
    @Nullable
    private MediaMetadata currentMetadata;

    /** Content/file URI of the currently displayed track, when played from local storage. */
    @Nullable
    private volatile Uri currentMediaUri;

    /** Version of the current track, for {v} of the track format. Null until it is looked up. */
    @Nullable
    private volatile TrackVersion currentVersion;

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

    private long lastVideoTimeSample = -1;
    private float playbackSpeed = 1f;
    private boolean playing;

    /**
     * A sample further than this from the estimate is a seek, which the estimate jumps to
     * instead of catching up with.
     */
    private static final long SEEK_THRESHOLD_MS = 1000;
    /** A longer frame gap, such as after the app was in the background, is not extrapolated over. */
    private static final long MAX_FRAME_STEP_MS = 100;

    // The position is sampled a few times a second, and estimated in between on every frame
    // so the highlight moves smoothly. The estimate only ever moves forward while playing.
    private long lastSamplePosition = -1;
    private long lastSampleTimeMs;
    private long lastCorrectedSampleTimeMs;
    private long lastFrameTimeUptimeMs = -1;
    private double estimate;
    private long smoothPosition;
    private boolean frameCallbackScheduled;
    /** Frames are only followed while something reads the position, such as an open lyrics panel. */
    private boolean positionReadSinceFrame;

    private final Choreographer.FrameCallback frameCallback = this::onFrame;

    private void onFrame(long frameTimeNanos) {
        frameCallbackScheduled = false;
        final long videoTime = VideoInformation.getVideoTime();
        if (videoTime >= 0 && videoTime != lastVideoTimeSample) {
            updateSample(videoTime, SystemClock.uptimeMillis());
        }

        if (!playing || lastSamplePosition < 0 || !positionReadSinceFrame) {
            lastFrameTimeUptimeMs = -1;
            return;
        }
        positionReadSinceFrame = false;

        final long frameTimeMs = frameTimeNanos / 1_000_000L;
        final float speed = Math.max(0.1f, playbackSpeed);
        if (lastFrameTimeUptimeMs == -1) {
            // Starting or resuming: carry the last sample forward by the time since it was taken.
            lastFrameTimeUptimeMs = frameTimeMs;
            estimate = Math.max(estimate, lastSamplePosition
                    + (SystemClock.uptimeMillis() - lastSampleTimeMs) * speed);
        }
        final long stepMs = Math.max(0, Math.min(MAX_FRAME_STEP_MS, frameTimeMs - lastFrameTimeUptimeMs));
        lastFrameTimeUptimeMs = frameTimeMs;
        estimate += stepMs * speed;

        // A new sample pulls a lagging estimate towards it, harder the further behind it is.
        if (lastSampleTimeMs != lastCorrectedSampleTimeMs) {
            lastCorrectedSampleTimeMs = lastSampleTimeMs;
            final double error = lastSamplePosition - estimate;
            if (Math.abs(error) > SEEK_THRESHOLD_MS) {
                estimate = lastSamplePosition;
            } else if (error > 500) {
                estimate += error * 0.8;
            } else if (error > 100) {
                estimate += error * 0.5;
            } else if (error > 0) {
                estimate += error * 0.3;
            }
        }

        estimate = Math.max(estimate, smoothPosition);
        smoothPosition = Math.round(estimate);
        ensureFrameCallback();
    }

    private void ensureFrameCallback() {
        if (!playing || frameCallbackScheduled) {
            return;
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Choreographer.getInstance().postFrameCallback(frameCallback);
            frameCallbackScheduled = true;
        } else {
            Utils.runOnMainThread(this::ensureFrameCallback);
        }
    }

    private void stopFrameCallback() {
        frameCallbackScheduled = false;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Choreographer.getInstance().removeFrameCallback(frameCallback);
        } else {
            Utils.runOnMainThread(this::stopFrameCallback);
        }
    }

    private int temporaryOffsetMs = 0;

    private LyricsManager() {
        VideoInformation.addVideoIdListener(videoId -> reloadCurrentTrack());
        LyricsPanelInstaller.registerSettingsListener();
        runOnFetchThread(LunaBeatProvider::preloadIndex);
        runOnFetchThread(() -> {
            MetadataCleaner.resolveSettingBlocking(LyricsFilterDefaults.LYRICS_CUSTOM_REGEX.get());
            MetadataCleaner.resolveSettingBlocking(LyricsFilterDefaults.LYRICS_TEXT_FILTER.get());
            MetadataCleaner.resolveSettingBlocking(LyricsFilterDefaults.LYRICS_CREDIT_LINE_REGEX.get());
        });
    }

    /**
     * Looks up the track's version on Deezer, only when the miniplayer or the media session
     * show the track format and it has {v}. If the lookup fails, the title shows without {v}.
     */
    private void requestVersion(TrackInfo track) {
        if (!Settings.LYRICS_ENABLED.get()
                || !(Settings.LYRICS_MINIPLAYER.get() || Settings.LYRICS_MEDIASESSION.get())
                || !MiniLyricsFormatter.usesVariant(Settings.LYRICS_MINI_TRACK_FORMAT.get())) {
            return;
        }
        TrackInfo query = TrackVersion.query(track, currentRawTitle, currentRawArtist);
        runOnLookupThread(() -> {
            TrackVersion version;
            try {
                version = DeezerProvider.fetchVersion(query);
            } catch (Exception ex) {
                Logger.printInfo(() -> "Deezer version lookup failed", ex);
                return;
            }
            if (track.equals(currentTrack)) {
                currentVersion = version;
            }
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

    /**
     * The video the lyrics on screen belong to. {@link #currentVideoId} is the video the
     * current track was committed for, which is not the same one while a rebinding still
     * keeps the lyric of the video that played before.
     */
    private String lyricsVideoId = "";

    @Nullable
    private String unconfirmedVideoId;

    private int rebindingGeneration;

    private boolean staleLyricsDropped;

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
    public TrackVersion getCurrentVersion() {
        return currentVersion;
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
    private void resetPosition() {
        stopFrameCallback();
        lastVideoTimeSample = -1;
        lastSamplePosition = -1;
        lastSampleTimeMs = 0;
        lastCorrectedSampleTimeMs = 0;
        lastFrameTimeUptimeMs = -1;
        estimate = 0;
        smoothPosition = 0;
    }

    private void updateSample(long newPosition, long now) {
        final long prevSample = lastSamplePosition;
        lastSamplePosition = newPosition;
        lastSampleTimeMs = now;
        lastVideoTimeSample = newPosition;
        if (prevSample < 0 || Math.abs(newPosition - prevSample) > SEEK_THRESHOLD_MS) {
            estimate = newPosition;
            smoothPosition = newPosition;
        }
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
        final long videoTime = VideoInformation.getVideoTime();

        if (videoTime >= 0 && videoTime != lastVideoTimeSample) {
            updateSample(videoTime, now);
        }

        if (!playing || lastSamplePosition < 0) {
            if (lastSamplePosition >= 0) {
                smoothPosition = lastSamplePosition;
                estimate = lastSamplePosition;
            }
            return Math.max(0, lastSamplePosition);
        }

        positionReadSinceFrame = true;
        ensureFrameCallback();
        return Math.max(0, smoothPosition);
    }

    public int getTemporaryOffsetMs() { return temporaryOffsetMs; }

    public void setTemporaryOffsetMs(int ms) {
        temporaryOffsetMs = ms;
        lastFrameTimeUptimeMs = -1;
    }

    /** Called from the panel when the track is about to change or to go away, not per drag frame. */
    public void persistTemporaryOffsetMs() {
        TrackInfo track = currentTrack;
        if (track == null) {
            return;
        }
        final int ms = temporaryOffsetMs;
        runOnLookupThread(() -> TrackOffsetStore.put(track, ms));
    }

    /** Injection point; main thread. */
    public void onSetMetadata(@Nullable MediaMetadata metadata) {
        Utils.verifyOnMainThread();
        if (metadata == null) {
            return;
        }
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
        unconfirmedVideoId = null;
        rebindingGeneration++;
        staleLyricsDropped = false;
        reloadCurrentTrack();
    }

    private void loadTrackOf(MediaMetadata metadata) {
        if (!Settings.LYRICS_ENABLED.get()) {
            return;
        }

        String rawTitle = metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
        String rawArtist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST);
        if (rawTitle == null || rawTitle.trim().isEmpty() || rawArtist == null || rawArtist.trim().isEmpty()) {
            // A later event names this video, so there is nothing to look up yet and the
            // lyric on screen belongs to whatever played before.
            dropStaleLyrics();
            return;
        }

        int durationSeconds = (int) (metadata.getLong(MediaMetadata.METADATA_KEY_DURATION) / 1000);

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
            SkipSegments.prefetch(videoId);
        }
        TrackInfo existingTrack = currentTrack;
        if (track.equals(existingTrack)) {
            if (pendingTrack != null && pendingTrack.track().equals(track)) {
                cancelPendingTrack();
            }
            if (videoId.equals(currentVideoId)) {
                currentRawTitle = rawTitle;
                currentRawArtist = rawArtist;
                currentMediaUri = mediaUri;
                if (track.durationSeconds() > 0
                        && track.durationSeconds() != existingTrack.durationSeconds()) {
                    currentTrack = track;
                }
                if (staleLyricsDropped && unconfirmedVideoId == null) {
                    deferTrackRebinding(videoId, rawTitle, rawArtist, mediaUri);
                }
                return;
            }
            currentRawTitle = rawTitle;
            currentRawArtist = rawArtist;
            currentMediaUri = mediaUri;
            deferTrackRebinding(videoId, rawTitle, rawArtist, mediaUri);
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
        if (!Settings.LYRICS_ENABLED.get() || currentTrack == null || pendingTrack != null
                || unconfirmedVideoId != null) {
            return;
        }
        String videoId = VideoInformation.getVideoId();
        if (videoId.isEmpty() || videoId.equals(loadedForVideoId)) {
            return;
        }
        currentVideoId = videoId;
        load(currentTrack, true);
    }

    /**
     * Holds the track while playback reached a video the metadata events have not
     * described yet. In video mode the session keeps the metadata of the video that
     * played before and only then comes back empty, so the next event is given its
     * chance to name the new video before anything is committed to it, which is what
     * keeps a previous lyric from staying on screen for the wrong one.
     */
    private void deferTrackRebinding(String videoId, String rawTitle, String rawArtist,
                                     @Nullable Uri mediaUri) {
        unconfirmedVideoId = videoId;
        rebindingGeneration++;
        final int generation = rebindingGeneration;
        Utils.runOnMainThreadDelayed(() -> confirmTrackRebinding(videoId, generation,
                rawTitle, rawArtist, mediaUri), REBINDING_CONFIRM_MS);
    }

    /**
     * Binds the current track to the video playback reached, keeping its lyric. Runs
     * once the metadata events have had their chance to name the new video.
     */
    private void confirmTrackRebinding(String videoId, int generation, String rawTitle,
                                       String rawArtist, @Nullable Uri mediaUri) {
        if (generation != rebindingGeneration || unconfirmedVideoId == null) {
            return;
        }
        String target = unconfirmedVideoId;
        unconfirmedVideoId = null;
        if (!target.equals(VideoInformation.getVideoId())) {
            return;
        }
        TrackInfo track = currentTrack;
        if (track == null) {
            return;
        }
        commitTrackChange(track, rawTitle, rawArtist, mediaUri, target, true);
    }

    private void dropStaleLyrics() {
        String videoId = VideoInformation.getVideoId();
        if (videoId.isEmpty()) {
            return;
        }
        // The lyric on screen belongs to this very video and nothing awaits another, so
        // nothing is stale.
        if (videoId.equals(lyricsVideoId) && unconfirmedVideoId == null) {
            return;
        }
        unconfirmedVideoId = null;
        rebindingGeneration++;
        requestId.incrementAndGet();
        cancelPendingFetches();
        cancelPendingTrack();
        suppressForRequest = -1;
        currentVideoId = videoId;
        loadedForVideoId = videoId;
        Lyrics shown = currentLyrics;
        if (shown != null && !shown.isEmpty()) {
            staleLyricsDropped = true;
        }
        setState(State.NOT_FOUND, null);
    }

    private void commitTrackChange(TrackInfo track, String rawTitle, String rawArtist,
                                   @Nullable Uri mediaUri, String videoId, boolean sameTrack) {
        unconfirmedVideoId = null;
        rebindingGeneration++;
        currentRawTitle = rawTitle;
        currentRawArtist = rawArtist;
        currentMediaUri = mediaUri;
        currentTrack = track;
        currentVideoId = videoId;
        if (!sameTrack) {
            currentVersion = null;
            requestVersion(track);
        }
        // Only reached on a real track change, so a loop cannot discard the offset it comes round for again.
        temporaryOffsetMs = TrackOffsetStore.get(track);
        // A rebinding keeps the previous track's lyric, which is what makes it worth
        // telling apart from the video the track was committed for.
        if (!sameTrack) {
            lyricsVideoId = videoId;
        }
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

    /** Injection point; main thread. */
    public void onSetPlaybackState(@Nullable PlaybackState playbackState) {
        Utils.verifyOnMainThread();
        if (playbackState == null) {
            return;
        }

        playing = playbackState.getState() == PlaybackState.STATE_PLAYING;

        final long newPosition = playbackState.getPosition();
        if (newPosition >= 0) {
            updateSample(newPosition, SystemClock.uptimeMillis());
        }

        final float speed = playbackState.getPlaybackSpeed();
        // A paused state reports speed zero, which would freeze the extrapolation even
        // after playback resumes, so only positive speeds are kept.
        if (speed > 0) {
            playbackSpeed = speed;
        }

        if (playing) {
            lastFrameTimeUptimeMs = -1;
        } else {
            stopFrameCallback();
            if (lastSamplePosition >= 0) {
                smoothPosition = lastSamplePosition;
                estimate = lastSamplePosition;
            }
        }
        ensureLoadedForCurrentVideo();
    }

    public void onDisplayedTrackChanged(@Nullable String title, @Nullable String artist, @Nullable Uri mediaUri) {
        Utils.verifyOnMainThread();
        // The mini player reports the track before it checks whether the feature is on,
        // so it must not be what decides to look lyrics up.
        if (!Settings.LYRICS_ENABLED.get()) {
            return;
        }
        if (title == null || title.trim().isEmpty() || artist == null || artist.trim().isEmpty()) {
            return;
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

        TrackInfo previous = currentTrack;
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
        suppressForRequest = -1;
        staleLyricsDropped = false;
        preferredFingerprint = null;
        rememberedQueue = null;
        searchQueryTitle = null;
        searchQueryArtist = null;
        LyricsRequests.setCustomMatchMode(false);
        preferenceVideoId = VideoInformation.getVideoId();
        loadedForVideoId = preferenceVideoId;
        displaySegs = null;

        // Started after this track's own lookup is on its way, so they never wait on
        // each other.
        LyricsPrefetcher.prefetchSuccessorOf(preferenceVideoId);

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
            String sbVideoId = skipSegmentsVideoId();
            List<SkipSegments.Seg> segs = SkipSegments.await(sbVideoId,
                    track.durationSeconds() * 1000L);
            if (id == requestId.get()) {
                displaySegsVideoId = sbVideoId;
                displaySegs = segs;
            }
            TrackInfo searchTrack = LyricsMatch.withEffectiveDuration(track, segs);
            phase2Done = false;
            if (pollAndPublishNext(id, track)) {
                return;
            }

            if (!phase2Done) {
                String order = Settings.LYRICS_SOURCE.get();
                List<LyricsProvider> providers = LyricsProviders.providersInOrder(order);
                // The queue still comes from the searched terms, so candidates are fetched
                // with those terms and those providers.
                TrackInfo queryTrack = customQueryTrack(searchTrack);
                if (queryTrack != null) {
                    providers = LyricsProviders.searchableProviders(providers);
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
                    && !shownFingerprints.contains(LyricsMatch.fingerprint(next.lyrics()))
                    && LyricsMatch.isValidLyrics(next.lyrics(), track)) {
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
        TrackInfo candidateTrack = LyricsRequests.isCustomMatchMode()
                ? new TrackInfo(track.title(), track.artist(), "", 0) : track;
        Set<String> existing = new HashSet<>(shownFingerprints);
        synchronized (candidateQueue) {
            for (ScoredCandidate sc : candidateQueue) {
                existing.add(LyricsMatch.fingerprint(sc.lyrics()));
            }
        }
        Lyrics current = currentLyrics;
        if (current != null) {
            existing.add(LyricsMatch.fingerprint(current));
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
                if (!LyricsMatch.isValidLyrics(candidate, track)) {
                    continue;
                }
                String fp = LyricsMatch.fingerprint(candidate);
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
    /**
     * Shows the lyric the user landed on for this track and arms the queue to be filled
     * around it instead of replacing it. Runs on a lookup thread, and reports whether the
     * lookup that called it has to treat everything it finds as queue material.
     */
    private boolean applyRememberedPreference(int id, TrackInfo track) {
        LyricsPreference preference = LyricsCache.getPreference(preferenceVideoId, track);
        if (preference == null || preference.preferred() == null) {
            return false;
        }
        Lyrics raw = preference.preferred();
        List<String> queue = preference.queue();
        String queryTitle = preference.queryTitle();
        String queryArtist = preference.queryArtist();
        String storedFingerprint = preference.fingerprint();
        if (!LyricsMatch.isValidLyrics(raw, track)) {
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
        Lyrics display = preferred;
        Utils.runOnMainThread(() -> {
            if (id != requestId.get()) {
                return;
            }
            if (display == null || display == Lyrics.NOT_FOUND || display.isEmpty()) {
                // Nothing survives the current filters. The lookup ahead was told to hold
                // back, so the suppression is released before it returns empty-handed.
                rememberPreferenceDisarmed();
                setState(State.NOT_FOUND, null);
                return;
            }
            setState(State.LOADED, display);
            shownFingerprints.add(LyricsMatch.fingerprint(display));
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
 * Shows lyrics that were computed outside any provider - by transcription or by aligning timings
 * onto text the user already had - as the version for this track, and moves everything that was
 * showing or waiting behind it.
 *
 * <p>This is what the other paths converge on, so the panel sees an ordinary load: the same
 * normalisation {@link #prepareForDisplay} applies, and the same remembered preference that pins
 * a chosen version across launches. Because the new version joins {@link #shownFingerprints},
 * refresh skips it like any other shown version and lands on what was pushed back.
 */
public void publishPreferred(@Nullable Lyrics injected) {
    Utils.verifyOnMainThread();
    if (injected == null || injected == Lyrics.NOT_FOUND || injected.isEmpty()) {
        return;
    }

    Lyrics display;
    try {
        display = prepareForDisplay(injected);
    } catch (Throwable ignored) {
        return;
    }
    if (display == null || display == Lyrics.NOT_FOUND || display.isEmpty()) {
        return;
    }

    // Everything lookup-related is about a version this one replaces.
    final int id = requestId.incrementAndGet();
    cancelPendingFetches();

    List<Lyrics> pushedBack = new ArrayList<>();
    Lyrics previous = currentLyrics;
    if (previous != null && !previous.isEmpty() && previous != Lyrics.NOT_FOUND) {
        pushedBack.add(previous);
    }
    synchronized (candidateQueue) {
        for (ScoredCandidate candidate : new ArrayList<>(candidateQueue)) {
            pushedBack.add(candidate.lyrics());
        }
        candidateQueue.clear();
    }

    String chosen = LyricsMatch.fingerprint(display);
    shownFingerprints.clear();
    shownFingerprints.add(chosen);
    rememberedQueue = null;
    preferredFingerprint = chosen;
    // Holds back anything still arriving for this request from taking the screen instead.
    suppressForRequest = id;

    setState(State.LOADED, display);

    for (Lyrics candidate : pushedBack) {
        if (candidate == null || candidate.isEmpty() || candidate == Lyrics.NOT_FOUND) {
            continue;
        }
        String fp = LyricsMatch.fingerprint(candidate);
        if (fp.equals(chosen)) {
            continue;
        }
        synchronized (candidateQueue) {
            boolean alreadyQueued = false;
            for (ScoredCandidate queued : candidateQueue) {
                if (fp.equals(LyricsMatch.fingerprint(queued.lyrics()))) {
                    alreadyQueued = true;
                    break;
                }
            }
            if (!alreadyQueued) {
                candidateQueue.add(new ScoredCandidate(
                        LyricsRequests.composite(LyricsRequests.NEUTRAL, candidate, 0),
                        LyricsRequests.syncRank(candidate), candidate));
            }
        }
    }

    LyricsPanelInstaller.enableLyricsButton();
    Utils.runOnMainThreadDelayed(LyricsPanelInstaller::onLyricsPanelDetected, 300);
    rememberPreference(display);
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
                int indexA = remembered.indexOf(LyricsMatch.fingerprint(a.lyrics()));
                int indexB = remembered.indexOf(LyricsMatch.fingerprint(b.lyrics()));
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
            fingerprints.add(LyricsMatch.fingerprint(candidate.lyrics()));
        }
        return fingerprints;
    }

    private void rememberPreference(Lyrics shown) {
        TrackInfo track = currentTrack;
        if (track == null || shown == null || shown == Lyrics.NOT_FOUND || shown.isEmpty()) {
            return;
        }
        String videoId = preferenceVideoId;
        String queryTitle = searchQueryTitle;
        String queryArtist = searchQueryArtist;
        String fingerprint = LyricsMatch.fingerprint(shown);
        List<String> queue = orderedQueueFingerprints();
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
        String incoming = LyricsMatch.fingerprint(lyrics);
        if (shownFingerprints.contains(incoming)) {
            return;
        }
        if (prepared == null || prepared == Lyrics.NOT_FOUND || prepared.isEmpty()) {
            return;
        }
        String preparedFp = LyricsMatch.fingerprint(prepared);
        if (shownFingerprints.contains(preparedFp)) {
            return;
        }
        Lyrics shown = currentLyrics;
        if (shown != null && preparedFp.equals(LyricsMatch.fingerprint(shown))) {
            return;
        }
        synchronized (candidateQueue) {
            if (shownFingerprints.contains(incoming) || shownFingerprints.contains(preparedFp)) {
                return;
            }
            for (ScoredCandidate candidate : candidateQueue) {
                if (LyricsMatch.fingerprint(candidate.lyrics()).equals(incoming)) {
                    return;
                }
            }
            int sync = LyricsRequests.syncRank(lyrics);
            candidateQueue.add(new ScoredCandidate(
                    LyricsRequests.composite(LyricsRequests.NEUTRAL, lyrics, 0), sync, lyrics));
        }
    }
    public boolean hasSearchProviders() {
        return !LyricsProviders.searchableProviders(
                LyricsProviders.providersInOrder(Settings.LYRICS_SOURCE.get())).isEmpty();
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
        TrackInfo track = currentTrack;
        if (track == null) {
            return;
        }
        cancelPendingTrack();
        String queryTitle = title != null ? title.trim() : "";
        String queryArtist = artist != null ? artist.trim() : "";
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

        // Video id providers only know the playing track, so they are asked only when the
        // terms are that track's own metadata.
        List<LyricsProvider> enabledProviders =
                LyricsProviders.providersInOrder(Settings.LYRICS_SOURCE.get());
        List<LyricsProvider> providers = defaultTerms
                ? enabledProviders
                : LyricsProviders.searchableProviders(enabledProviders);
        if (providers.isEmpty()) {
            publishFromLookup(id, Lyrics.NOT_FOUND);
            return;
        }

        runOnLookupThread(() -> {
            String sbVideoId = skipSegmentsVideoId();
            List<SkipSegments.Seg> segs = SkipSegments.await(sbVideoId,
                    track.durationSeconds() * 1000L);
            if (id == requestId.get()) {
                displaySegsVideoId = sbVideoId;
                displaySegs = segs;
            }
            TrackInfo searchTrack = LyricsMatch.withEffectiveDuration(track, segs);
            TrackInfo queryTrack = defaultTerms
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
                shownFingerprints.add(LyricsMatch.fingerprint(current));
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
            boolean validResult = LyricsMatch.isValidLyrics(result, track);

            seedCandidateQueue(id, fetchResult.scored, validResult, result);
            resnapshotPreference(id);

            if (id != requestId.get()) {
                return;
            }
            if (validResult) {
                if (!LyricsRequests.isCustomMatchMode()) {
                    LyricsCache.put(track, LyricsMatch.cacheSourceFor(result), result, preferenceVideoId);
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
        String sbVideoId = skipSegmentsVideoId();
        List<SkipSegments.Seg> segs = SkipSegments.await(sbVideoId,
                trackParam.durationSeconds() * 1000L);
        if (id == requestId.get()) {
            displaySegsVideoId = sbVideoId;
            displaySegs = segs;
        }
        TrackInfo track = LyricsMatch.withEffectiveDuration(trackParam, segs);
        final boolean suppressed = applyRememberedPreference(id, track);

        // Local files take priority: read embedded LYRICS/LYRIC tags before hitting providers.
        if (Settings.LYRICS_USE_EMBEDDED.get()) {
            Uri embeddedUri = localUriFor(track);
            if (embeddedUri != null) {
                Lyrics embedded = LocalLyricsFetcher.fetch(embeddedUri);
                if (embedded != null) {
                    // Nothing reads the cache under this name: embedded lyrics come from the
                    // file every time, so writing them only fills a slot that is never answered.
                    publishFromLookup(id, embedded);
                    if (!suppressed) {
                        return;
                    }
                }
            }
        }

        String order = Settings.LYRICS_SOURCE.get();
        List<LyricsProvider> providers = LyricsProviders.providersInOrder(order);
        TrackInfo queryTrack = customQueryTrack(track);
        if (queryTrack != null) {
            // The queue is being rebuilt from custom terms, which the providers keyed by
            // video id cannot answer. The cache is keyed by the playing track and holds what
            // the default terms found, so its misses say nothing about these terms.
            providers = LyricsProviders.searchableProviders(providers);
        }
        if (providers.isEmpty()) {
            publishFromLookup(id, Lyrics.NOT_FOUND);
            return;
        }

        int notFoundCached = 0;
        for (LyricsProvider provider : providers) {
            Lyrics cached = LyricsCache.get(track, provider.name(), preferenceVideoId);
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

        // Custom terms carry none of the playing track's InnerTube metadata, which says
        // nothing about them.
        Tiers tiers = queryTrack != null
                ? buildTiers(queryTrack, queryTrack.title())
                : buildTiers(track, currentRawTitle);

        LookupResult fetchResult = fetchFromProviders(id, tiers.original(), tiers.derived(),
                failed, providers, false);
        if (id != requestId.get()) return;
        result = fetchResult.best;

        boolean validResult = LyricsMatch.isValidLyrics(result, track);
        if (validResult) {
            if (!LyricsRequests.isCustomMatchMode()) {
                LyricsCache.put(track, LyricsMatch.cacheSourceFor(result), result, preferenceVideoId);
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

        // A miss on the track's own terms marks the provider as having nothing; a miss on
        // typed terms says nothing about them and leaves the cache untouched.
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
                LyricsCache.put(track, provider.name(), Lyrics.NOT_FOUND, preferenceVideoId);
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
            if (LyricsMatch.isBlindProvider(p)) {
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
                existing.add(LyricsMatch.fingerprint(sc.lyrics()));
            }
            Lyrics current = currentLyrics;
            if (current != null) {
                existing.add(LyricsMatch.fingerprint(current));
            }
            for (ScoredCandidate sc : collected) {
                if (id == requestId.get() && existing.add(LyricsMatch.fingerprint(sc.lyrics()))) {
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
            if (LyricsMatch.isBlindProvider(p)) {
                blind.add(p);
            } else {
                nonBlind.add(p);
            }
        }
        final int stage1Count = Math.min(4, nonBlind.size());
        List<LyricsProvider> stage1Providers = nonBlind.subList(0, stage1Count);
        List<LyricsProvider> stage2Providers = nonBlind.subList(
                stage1Count, nonBlind.size());

        CompletionService<ProviderFetch> cs = new ExecutorCompletionService<>(fetchExecutor);
        List<Future<ProviderFetch>> futures = new ArrayList<>();
        Set<String> providersWithResults = ConcurrentHashMap.newKeySet();
        Set<String> providersFailed = ConcurrentHashMap.newKeySet();
        Set<String> providersAttempted = ConcurrentHashMap.newKeySet();
        AtomicBoolean threadFailed = new AtomicBoolean(false);

        List<ScoredCandidate> scoreCandidates = new ArrayList<>();
        DisplayState display = new DisplayState(wordSync, exhaustive);

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
                publishBest(id, display);
                continue;
            }
            completed++;
            try {
                boolean complete = display.accept(f.get(), scoreCandidates, stage12);
                publishBest(id, display);
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

    private static final int FIRST_PAINT_HOLD_MS = 800;

    /**
     * The word-timed version of the same lyric usually lands moments after the line-synced one,
     * so a first result that carries only line timing waits out {@link #FIRST_PAINT_HOLD_MS}
     * rather than render the song twice.
     */
    private void publishBest(int id, DisplayState display) {
        Lyrics available = display.bestResult != null
                ? display.bestResult : display.bestFallback;
        if (available == null || available == display.published) {
            return;
        }
        final long stamp = SystemClock.uptimeMillis();
        if (display.firstPaintAt == 0L) {
            display.firstPaintAt = stamp;
        }
        final boolean holding = display.wordSync
                && display.published == null
                && !display.exhaustive
                && stamp - display.firstPaintAt < FIRST_PAINT_HOLD_MS
                && LyricsRequests.syncRank(available) < 2;
        if (holding) {
            return;
        }
        display.published = available;
        publishFromLookup(id, available);
    }
    record ProviderFetch(@Nullable Lyrics lyrics, int matchScore, boolean highMatch,
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
        Lyrics raw = lyrics;
        Lyrics prepared = display;
        Utils.runOnMainThread(() -> {
            Lyrics current = currentLyrics;
            if (id == requestId.get() && state == State.LOADED
                    && current != null && prepared != null
                    && LyricsMatch.fingerprint(current).equals(LyricsMatch.fingerprint(prepared))) {
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
            incomingFp = LyricsMatch.fingerprint(raw);
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
            shownFingerprints.add(LyricsMatch.fingerprint(prepared));
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
        return videoId;
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
        SkipSegments.Seg seg = dialogPreviewSeg;
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
        if (LyricsMatch.isVideoIdMatchedLyrics(currentLyrics)) {
            return null;
        }
        String videoId = skipSegmentsVideoId();
        if (videoId == null) {
            return null;
        }
        TrackInfo track = currentTrack;
        List<SkipSegments.Seg> base = SkipSegments.peek(videoId,
                track == null ? 0 : track.durationSeconds() * 1000L);
        if (base != null) {
            displaySegsVideoId = videoId;
            displaySegs = base;
        } else {
            List<SkipSegments.Seg> latched = displaySegs;
            base = latched != null && videoId.equals(displaySegsVideoId) ? latched : null;
            if (base == null) {
                SkipSegments.prefetch(videoId);
            }
        }
        SkipSegments.Seg preview = dialogPreviewSeg;
        if (preview == null || !videoId.equals(dialogPreviewVideoId)) {
            return base;
        }
        return SkipSegments.withSegment(base, preview);
    }
    /**
     * Maps a lyric timestamp onto the video timeline a seek has to target: the inverse of
     * the subtraction {@link #getPositionMs()} applies before it reads a lyric time.
     */
    public long toVideoTimeMs(long lyricTimeMs) {
        List<SkipSegments.Seg> segs = syncSegs();
        if (segs == null || segs.isEmpty()) {
            return lyricTimeMs;
        }
        return SkipSegments.remapTimestamp(lyricTimeMs, segs);
    }
    /**
     * Filters out credit lines and text the user asked to hide, then normalizes the timings
     * of synced lyrics, consulting the short-lived cache of already filtered results.
     */
    private Lyrics prepareForDisplay(Lyrics lyrics) {
        String cacheKey = null;
        if (lyrics != null && lyrics != Lyrics.NOT_FOUND && !lyrics.isEmpty()) {
            StringBuilder sb = new StringBuilder(lyrics.providerName()).append('|');
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
            lyrics = LyricsFilter.filterCreditLines(lyrics, currentTrack);
            lyrics = LyricsFilter.filterLyricsText(lyrics);
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
                    lyrics.formatType(), lyrics.sourceUrl(), lyrics.about());
        }
        return lyrics;
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
        List<VariantQuery> fillOriginal = List.copyOf(original);
        List<VariantQuery> fillDerived = List.copyOf(derived);
        List<LyricsProvider> fillProviders = List.copyOf(providers);
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
                    if (!shownFingerprints.contains(LyricsMatch.fingerprint(sc.lyrics()))) {
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
            // The app never builds its own lyrics into the panel for a music video, so an
            // open one is covered from here instead.
            LyricsPanelInstaller.onLyricsPanelDetected();
            LyricsPanelInstaller.enableLyricsButton();
            detectSections(lyrics);
        }
    }

    /**
     * Asks the AI for the sections of lyrics that ship without any, and puts them on screen once it
     * answers. A track change while the request is out would leave them on the wrong song, so the
     * callback checks the lyrics and the track it was asked about are still the current ones.
     */
    @SuppressWarnings({"UnnecessaryLocalVariable", "ConstantValue"})
    private void detectSections(@Nullable Lyrics lyrics) {
        if (!Settings.LYRICS_USE_AI_SECTION.get()) {
            return;
        }
        if (!SectionDetector.needsDetection(lyrics)) {
            return;
        }
        TrackInfo track = currentTrack;
        Lyrics target = lyrics;
        if (track == null || target == null) {
            return;
        }
        String fingerprint = LyricsMatch.fingerprint(target);
        if (fingerprint.equals(sectionsPendingFingerprint)) {
            return;
        }
        sectionsPendingFingerprint = fingerprint;
        SectionDetector.detect(track, target,
                () -> fingerprint.equals(sectionsPendingFingerprint), sections -> {
            if (sectionsPendingFingerprint != null
                    && Objects.equals(sectionsPendingFingerprint, fingerprint)) {
                sectionsPendingFingerprint = null;
            }
            Lyrics shown = currentLyrics;
            boolean stillShowing = shown != null && fingerprint.equals(LyricsMatch.fingerprint(shown));
            if (sections == null || !stillShowing || currentTrack != track) {
                return;
            }
            setState(State.LOADED, SectionDetector.applySections(shown, sections));
        });
    }

    public boolean areLyricsSynced() {
        Lyrics lyrics = currentLyrics;
        return lyrics != null && lyrics.synced() && !lyrics.isEmpty();
    }

    /**
     * The text the miniplayer and the media session show for the current position.
     * See {@link LyricsPresentation#miniLine}.
     */
    @Nullable
    public String getCurrentLineText() {
        Lyrics lyrics = currentLyrics;
        if (lyrics == null || !lyrics.synced() || lyrics.isEmpty()) {
            return null;
        }
        TrackInfo track = currentTrack;
        final long durationMs = track == null ? 0 : track.durationSeconds() * 1000L;
        return LyricsPresentation.miniLine(lyrics, durationMs, getPositionMs(),
                currentRawTitle, currentRawArtist);
    }

}
