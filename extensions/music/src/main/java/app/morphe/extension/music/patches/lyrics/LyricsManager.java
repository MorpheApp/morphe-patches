/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2269
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics;

import android.media.MediaMetadata;
import android.media.session.PlaybackState;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import app.morphe.extension.music.patches.lyrics.requests.CharactersConverter;
import app.morphe.extension.music.patches.lyrics.requests.SubtitlesFetcher;
import app.morphe.extension.music.patches.lyrics.requests.KuGouProvider;
import app.morphe.extension.music.patches.lyrics.requests.LrcLibProvider;
import app.morphe.extension.music.patches.lyrics.requests.LyricsProvider;
import app.morphe.extension.music.patches.lyrics.requests.NetEaseProvider;
import app.morphe.extension.music.patches.lyrics.requests.QQProvider;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.music.shared.VideoInformation;
import app.morphe.extension.shared.sponsorblock.SegmentPlaybackController;
import app.morphe.extension.shared.sponsorblock.objects.SponsorSegment;
import app.morphe.extension.shared.Logger;
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

    /** A single thread is enough, and it keeps the requests for one track ordered. */
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final List<Listener> listeners = new ArrayList<>(2);

    private static final Lyrics EMPTY_SUBTITLES =
            new Lyrics(Collections.emptyList(), Lyrics.CAPTIONS_PROVIDER, true);

    /**
     * Strings some providers or captions return for instrumental tracks, indicating the
     * track has no actual lyrics. A result made up entirely of these is discarded.
     */
    private static final Set<String> INSTRUMENTAL_PLACEHOLDERS = new HashSet<>(Arrays.asList(
            "此歌曲为没有填词的纯音乐，请您欣赏",
            "纯音乐，请欣赏",
            "《纯音乐，请欣赏》"
    ));

    @Nullable
    private TrackInfo currentTrack;

    @Nullable
    private Lyrics currentLyrics;

    /** Raw lyrics in the video timeline domain, before SponsorBlock auto-skip remapping. */
    @Nullable
    private Lyrics rawLyrics;

    /** Identity of the last segment set used to remap, to detect segment changes cheaply. */
    @Nullable
    private Object lastSegmentRef;

    private State state = State.IDLE;

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

    private LyricsManager() {
    }

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

    static boolean isInstrumental(@Nullable Lyrics lyrics) {
        if (lyrics == null || lyrics == Lyrics.NOT_FOUND || lyrics.isEmpty()) {
            return false;
        }
        for (LyricsLine line : lyrics.lines()) {
            final String text = line.text();
            if (text != null && !text.isBlank() && isPlaceholderLine(text)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPlaceholderLine(@Nullable String text) {
        if (text == null) {
            return false;
        }
        final String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        for (String placeholder : INSTRUMENTAL_PLACEHOLDERS) {
            if (trimmed.contains(placeholder)) {
                return true;
            }
        }
        return false;
    }

    /** Whether the lyrics are usable (non-null, non-empty and not instrumental). */
    static boolean isValidLyrics(@Nullable Lyrics lyrics) {
        return lyrics != null
                && lyrics != Lyrics.NOT_FOUND
                && !lyrics.isEmpty()
                && !isInstrumental(lyrics);
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
        // Captions (subtitles) are shown verbatim: no user delay and no SponsorBlock remap.
        if (currentLyrics != null && currentLyrics.isSubtitles()) {
            return position;
        }
        final long contentPosition = position - Settings.LYRICS_OFFSET_MS.get();
        maybeRemapForSegments();
        return toContentTime(contentPosition);
    }

    /**
     * Injection point relay. Called on the main thread.
     */
    public void onSetMetadata(@Nullable MediaMetadata metadata) {
        Utils.verifyOnMainThread();
        if (metadata == null || !Settings.LYRICS_ENABLED.get()) {
            return;
        }

        String rawTitle = metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
        String rawArtist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST);
        if (rawTitle == null || rawTitle.isBlank() || rawArtist == null || rawArtist.isBlank()) {
            return;
        }

        TrackInfo track = new TrackInfo(
                MetadataCleaner.cleanTitle(rawTitle),
                MetadataCleaner.cleanArtist(rawArtist),
                MetadataCleaner.cleanAlbum(metadata.getString(MediaMetadata.METADATA_KEY_ALBUM)),
                (int) (metadata.getLong(MediaMetadata.METADATA_KEY_DURATION) / 1000)
        );

        if (track.title().isEmpty() || track.artist().isEmpty()) {
            return;
        }

        if (track.equals(currentTrack)) {
            return;
        }

        currentTrack = track;
        // A new track starts at zero, and the first playback state update corrects it.
        positionMs = 0;
        positionUpdatedAtUptimeMs = SystemClock.uptimeMillis();
        lastVideoTimeSample = -1;

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

        final float speed = playbackState.getPlaybackSpeed();
        // A paused state reports a speed of zero, which would freeze extrapolation
        // even after playback resumes, so only positive speeds are kept.
        if (speed > 0) {
            playbackSpeed = speed;
        }
    }

    public void onDisplayedTrackChanged(@Nullable String title, @Nullable String artist) {
        Utils.verifyOnMainThread();
        if (title == null || title.isBlank() || artist == null || artist.isBlank()) {
            return;
        }

        final String cleanedTitle = MetadataCleaner.cleanTitle(title);
        final String cleanedArtist = MetadataCleaner.cleanArtist(artist);
        if (cleanedTitle.isEmpty() || cleanedArtist.isEmpty()) {
            return;
        }

        if (currentTrack != null
                && currentTrack.title().equals(cleanedTitle)
                && currentTrack.artist().equals(cleanedArtist)) {
            return;
        }

        currentTrack = new TrackInfo(cleanedTitle, cleanedArtist, "", 0);
        positionMs = 0;
        positionUpdatedAtUptimeMs = SystemClock.uptimeMillis();
        lastVideoTimeSample = -1;
        load(currentTrack);
    }

    public void clearLyrics() {
        Utils.verifyOnMainThread();
        setState(State.IDLE, null);
    }

    private void load(TrackInfo track) {
        final int id = ++requestId;
        setState(State.LOADING, null);

        final Lyrics cachedSubtitles = LyricsCache.get(track, LyricsSource.SUBTITLES);
        if (cachedSubtitles != null) {
            if (cachedSubtitles == Lyrics.NOT_FOUND) {
                executor.execute(() -> runProviderLookup(id, track));
                return;
            }
            final Lyrics subtitles = cachedSubtitles;
            Utils.runOnMainThread(() -> {
                if (id != requestId) {
                    return;
                }
                publish(id, subtitles);
                if (!subtitles.isEmpty()) {
                    translateSubtitles(id, track, subtitles);
                }
            });
            return;
        }

        executor.execute(() -> {
            final SubtitlesFetcher.SubtitlesOutcome outcome = SubtitlesFetcher.fetch();
            if (outcome.lyrics != null) {
                if (isInstrumental(outcome.lyrics)) {
                    // Captions exist but are only instrumental placeholders - no real lyrics.
                    LyricsCache.put(track, LyricsSource.SUBTITLES, Lyrics.NOT_FOUND);
                    runProviderLookup(id, track);
                    return;
                }
                LyricsCache.put(track, LyricsSource.SUBTITLES, outcome.lyrics);
                Utils.runOnMainThread(() -> {
                    if (id != requestId) {
                        return;
                    }
                    publish(id, outcome.lyrics);
                    translateSubtitles(id, track, outcome.lyrics);
                });
                return;
            }

            if (outcome.suppressProviders) {
                LyricsCache.put(track, LyricsSource.SUBTITLES, EMPTY_SUBTITLES);
                Utils.runOnMainThread(() -> {
                    if (id != requestId) {
                        return;
                    }
                    publish(id, EMPTY_SUBTITLES);
                });
                return;
            }

            LyricsCache.put(track, LyricsSource.SUBTITLES, Lyrics.NOT_FOUND);
            runProviderLookup(id, track);
        });
    }

    private void runProviderLookup(int id, TrackInfo track) {
        final LyricsSource source = Settings.LYRICS_SOURCE.get();
        Lyrics cached = LyricsCache.get(track, source);
        if (cached != null) {
            Utils.runOnMainThread(() -> publish(id, cached));
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

        final boolean[] failed = {false};
        Lyrics result = fetchFromProviders(track, failed, source);

        if (!isValidLyrics(result)) {
            for (TrackInfo variant : CharactersConverter.variants(track)) {
                final Lyrics fetched = fetchFromProviders(variant, failed, source);
                if (isValidLyrics(fetched)) {
                    result = fetched;
                    break;
                }
            }
        }

        if (isValidLyrics(result)) {
            final Lyrics toPublish = result;
            LyricsCache.put(track, source, toPublish);
            Utils.runOnMainThread(() -> publish(id, toPublish));
            return;
        }

        LyricsCache.put(track, source, Lyrics.NOT_FOUND);
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

    private void translateSubtitles(int id, TrackInfo track, Lyrics subtitles) {
        LyricsTranslator.translate(track, subtitles, LyricsSource.SUBTITLES, translated -> {
            if (id != requestId || translated == null) {
                return;
            }

            List<LyricsLine> original = subtitles.lines();
            List<LyricsLine> lines = new ArrayList<>(original.size());
            for (int i = 0; i < original.size(); i++) {
                String text = original.get(i).text();
                if (i < translated.size()) {
                    String t = translated.get(i);
                    if (t != null && !t.isEmpty()) {
                        text = t;
                    }
                }
                lines.add(new LyricsLine(original.get(i).startTimeMs(), text));
            }
            publish(id, new Lyrics(lines, subtitles.providerName(), true));
        });
    }

    /**
     */
    @Nullable
    private Lyrics fetchFromProviders(TrackInfo track, boolean[] failed, LyricsSource source) {
        Lyrics result = null;
        int bestRank = -1; // -1 = none, 0 = plain, 1 = line-synced, 2 = word-synced
        for (LyricsProvider provider : providersInOrder(source)) {
            Lyrics fetched = null;
            try {
                fetched = provider.fetch(track);
            } catch (Exception ex) {
                failed[0] = true;
                Logger.printInfo(() -> "Lyrics request failed: " + provider.name(), ex);
            }

            if (!isValidLyrics(fetched)) {
                continue;
            }

            final int rank = rankOf(fetched);
            if (rank > bestRank) {
                bestRank = rank;
                result = fetched;
                if (rank == 2) {
                    break; // word-synced is the best possible tier
                }
            }
        }
        return result;
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
            Logger.printDebug(() -> "Discarding lyrics of a previous track");
            return;
        }

        if (lyrics == Lyrics.NOT_FOUND || lyrics.isEmpty()) {
            rawLyrics = null;
            lastSegmentRef = null;
            setState(State.NOT_FOUND, null);
        } else {
            rawLyrics = lyrics;
            lastSegmentRef = null; // Force a fresh remap for the current segment set.
            Lyrics displayed = remapLyrics(lyrics);
            setState(State.LOADED, displayed);
            LyricsPanelInstaller.enableLyricsButton();
            Utils.runOnMainThreadDelayed(() -> LyricsPanelInstaller.onLyricsPanelDetected(), 300);
            Logger.printInfo(() -> "Lyrics loaded: source=" + lyrics.providerName()
                    + " subtitles=" + lyrics.isSubtitles()
                    + " lines=" + lyrics.lines().size());
        }
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

    private static List<LyricsProvider> providersInOrder(LyricsSource source) {
        List<LyricsProvider> providers = new ArrayList<>(4);
        switch (source) {
            case LRCLIB:
                providers.add(new LrcLibProvider());
                break;
            case KUGOU:
                providers.add(new KuGouProvider());
                break;
            case NETEASE:
                providers.add(new NetEaseProvider());
                break;
            case QQ:
                providers.add(new QQProvider());
                break;
            case SUBTITLES:
                break;
            case LRCLIB_THEN_KUGOU:
                providers.add(new LrcLibProvider());
                providers.add(new QQProvider());
                providers.add(new KuGouProvider());
                providers.add(new NetEaseProvider());
                break;
            default:
                providers.add(new LrcLibProvider());
                providers.add(new QQProvider());
                providers.add(new KuGouProvider());
                providers.add(new NetEaseProvider());
                break;
        }
        return providers;
    }

    /** True when the SponsorBlock extension is enabled and the video has segments to remap. */
    private static boolean sbActive() {
        return Settings.SB_ENABLED.get() && SegmentPlaybackController.videoHasSegments();
    }

    private static boolean currentLyricsAreSubtitles() {
        final Lyrics current = LyricsManager.getInstance().currentLyrics;
        return current != null && current.isSubtitles();
    }

    /** A segment is remapped out of the timeline only when it auto-skips. */
    private static boolean isAutoSkip(@NonNull SponsorSegment segment) {
        return segment.category.behaviour.skipAutomatically;
    }

    private static long toContentTime(long videoMs) {
        if (videoMs <= 0 || currentLyricsAreSubtitles()) {
            return videoMs;
        }
        if (!sbActive()) {
            return videoMs;
        }
        final SponsorSegment[] segments = SegmentPlaybackController.getSegments();
        if (segments == null || segments.length == 0) {
            return videoMs;
        }
        long content = videoMs;
        for (SponsorSegment segment : segments) {
            if (isAutoSkip(segment)) {
                if (segment.end <= videoMs) {
                    content -= segment.length();
                } else if (segment.start > videoMs) {
                    break;
                }
            }
        }
        return content;
    }

    public long toVideoTime(long contentMs) {
        // Subtitles are never remapped around SponsorBlock segments.
        if (contentMs <= 0 || currentLyricsAreSubtitles()) {
            return contentMs;
        }
        if (!sbActive()) {
            return contentMs;
        }
        final SponsorSegment[] segments = SegmentPlaybackController.getSegments();
        if (segments == null || segments.length == 0) {
            return contentMs;
        }
        long video = contentMs;
        for (SponsorSegment segment : segments) {
            if (isAutoSkip(segment) && segment.start <= video) {
                video += segment.length();
            } else if (segment.start > video) {
                break;
            }
        }
        return video;
    }

    /** True if the given video time falls inside an auto-skipped segment. */
    private static boolean isInsideAutoSkip(long videoMs) {
        if (!sbActive()) {
            return false;
        }
        final SponsorSegment[] segments = SegmentPlaybackController.getSegments();
        if (segments == null) {
            return false;
        }
        for (SponsorSegment segment : segments) {
            if (isAutoSkip(segment) && segment.start <= videoMs && videoMs < segment.end) {
                return true;
            }
        }
        return false;
    }

    /** Re-derives the displayed lyrics in the content timeline from {@link #rawLyrics}. */
    @NonNull
    private static Lyrics remapLyrics(@NonNull Lyrics lyrics) {
        if (!lyrics.synced() || lyrics.isEmpty()) {
            return lyrics;
        }
        if (lyrics.isSubtitles()) {
            return lyrics;
        }
        final SponsorSegment[] segments = sbActive() ? SegmentPlaybackController.getSegments() : null;
        boolean hasSkip = false;
        if (segments != null) {
            for (SponsorSegment segment : segments) {
                if (isAutoSkip(segment)) {
                    hasSkip = true;
                    break;
                }
            }
        }
        if (!hasSkip) {
            return lyrics;
        }
        final int size = lyrics.lines().size();
        final List<LyricsLine> mapped = new ArrayList<>(size);
        for (LyricsLine line : lyrics.lines()) {
            final long rawStart = line.startTimeMs();
            if (isInsideAutoSkip(rawStart)) {
                continue; // The line plays during a skipped segment and is never shown.
            }
            final long start = toContentTime(rawStart);
            final List<Word> words = line.words();
            if (words.isEmpty()) {
                mapped.add(new LyricsLine(start, line.text(), words));
                continue;
            }
            final List<Word> mappedWords = new ArrayList<>(words.size());
            for (Word word : words) {
                if (isInsideAutoSkip(word.startMs()) || isInsideAutoSkip(word.endMs())) {
                    continue; // Drop words that fall inside a skipped segment.
                }
                final long wordStart = toContentTime(word.startMs());
                final long wordEnd = toContentTime(word.endMs());
                if (wordEnd <= wordStart) {
                    continue;
                }
                mappedWords.add(new Word(wordStart, wordEnd, word.text()));
            }
            mapped.add(new LyricsLine(start, line.text(), mappedWords));
        }
        return new Lyrics(mapped, lyrics.providerName(), true);
    }

    private void maybeRemapForSegments() {
        if (state != State.LOADED || rawLyrics == null) {
            lastSegmentRef = null;
            return;
        }
        if (!sbActive()) {
            lastSegmentRef = null;
            return;
        }
        final SponsorSegment[] segments = SegmentPlaybackController.getSegments();
        if (segments == lastSegmentRef) {
            return; // Same segment set as last remap; nothing to do.
        }
        lastSegmentRef = segments; // A reference write is benign across threads.
        Utils.runOnMainThread(() -> {
            final Lyrics remapped = remapLyrics(rawLyrics);
            if (remapped == currentLyrics) {
                return;
            }
            currentLyrics = remapped;
            for (Listener listener : new ArrayList<>(listeners)) {
                try {
                    listener.onLyricsChanged(State.LOADED, remapped);
                } catch (Exception ex) {
                    Logger.printException(() -> "Lyrics listener failure", ex);
                }
            }
        });
    }
}
