/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2269
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.requests;

import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.List;

import app.morphe.extension.music.patches.lyrics.Lyrics;
import app.morphe.extension.music.patches.lyrics.TrackInfo;

/**
 * A third party lyrics backend.
 */
public interface LyricsProvider {

    /**
     * Provider name shown to the user as the lyrics source.
     */
    String name();

    record FetchResult(Lyrics lyrics,
                       @Nullable String sourceTitle,
                       @Nullable String sourceArtist,
                       long sourceDurationSec,
                       @Nullable TrackInfo queriedVariant,
                       boolean videoIdKeyed) {

        @Nullable
        public static FetchResult of(@Nullable Lyrics lyrics) {
            return lyrics == null ? null
                    : new FetchResult(lyrics, null, null, 0, null, true);
        }

        @Nullable
        public static FetchResult blind(@Nullable Lyrics lyrics) {
            return lyrics == null ? null
                    : new FetchResult(lyrics, null, null, 0, null, false);
        }

        @Nullable
        public static FetchResult of(@Nullable Lyrics lyrics, TrackInfo queriedVariant) {
            if (lyrics == null) {
                return null;
            }
            return new FetchResult(lyrics,
                    queriedVariant != null ? queriedVariant.title() : null,
                    queriedVariant != null ? queriedVariant.artist() : null,
                    queriedVariant != null ? queriedVariant.durationSeconds() : 0L,
                    queriedVariant,
                    false);
        }

        @Nullable
        public static FetchResult of(@Nullable Lyrics lyrics, String title, String artist,
                                     long durationSec, TrackInfo queriedVariant) {
            if (lyrics == null) {
                return null;
            }
            return new FetchResult(lyrics, title, artist, durationSec, queriedVariant, false);
        }

        public int matchScore(TrackInfo track) {
            if (sourceTitle != null && !sourceTitle.isEmpty()) {
                return LyricsRequests.evaluate(sourceTitle, sourceArtist, sourceDurationSec,
                        track).score();
            }
            if (queriedVariant != null) {
                return LyricsRequests.evaluate(queriedVariant.title(), queriedVariant.artist(),
                        queriedVariant.durationSeconds(), track).score();
            }
            return videoIdKeyed ? LyricsRequests.VIDEO_ID_TRUST : LyricsRequests.NEUTRAL;
        }

        /** Whether this result clears the high-match gate for first display. */
        public boolean isHighMatch(TrackInfo track) {
            if (sourceTitle != null && !sourceTitle.isEmpty()) {
                return LyricsRequests.isHighMatch(LyricsRequests.evaluate(
                        sourceTitle, sourceArtist, sourceDurationSec, track));
            }
            if (queriedVariant != null) {
                return LyricsRequests.isHighMatch(LyricsRequests.evaluate(
                        queriedVariant.title(), queriedVariant.artist(),
                        queriedVariant.durationSeconds(), track));
            }
            if (videoIdKeyed) {
                return LyricsRequests.isHighMatch(LyricsRequests.MatchVerdict.videoIdTrust());
            }
            return false;
        }
    }

    /**
     * Fetches lyrics. Always called off the main thread.
     *
     * @return result, or {@code null} if this provider has none for the track.
     */
    @Nullable
    FetchResult fetch(TrackInfo track) throws Exception;

    /**
     * Returns scored candidate lyrics for the track, best first.
     * Scores use the shared match+sync scale from {@link LyricsRequests}.
     */
    default List<Lyrics.ScoredLyrics> fetchCandidates(TrackInfo track) throws Exception {
        FetchResult result = fetch(track);
        if (result == null || result.lyrics() == null || result.lyrics() == Lyrics.NOT_FOUND) {
            return Collections.emptyList();
        }
        int score = result.matchScore(track) + LyricsRequests.syncRank(result.lyrics());
        return Collections.singletonList(new Lyrics.ScoredLyrics(score, result.lyrics()));
    }

    /**
     * Whether this provider supports multiple candidates per track.
     */
    default boolean hasCandidates() {
        return false;
    }
}
