/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2625
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.requests;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import app.morphe.extension.music.patches.lyrics.model.Lyrics;
import app.morphe.extension.music.patches.lyrics.model.TrackInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * A provider whose own search already ranks its results, so the top one answers the plain lookup
 * and the rest fill the candidate queue. Subclasses supply the search and nothing else.
 */
abstract class ScoredSongProvider implements LyricsProvider {

    /**
     * One hit of the provider's own search, with the metadata it came under. {@code durationSec}
     * is 0 when the provider reports none, which leaves the duration filter to the other fields.
     */
    protected record ScoredSong(int score, Lyrics lyrics, String title, String artist,
                                long durationSec) {
    }

    /** The hits for this track, best first, empty when the search found none. */
    @NonNull
    protected abstract List<ScoredSong> fetchScoredSongs(TrackInfo track) throws Exception;

    @Override
    public boolean hasCandidates() {
        return true;
    }

    @Nullable
    @Override
    public FetchResult fetch(TrackInfo track) throws Exception {
        List<ScoredSong> candidates = fetchScoredSongs(track);
        if (candidates.isEmpty()) {
            return null;
        }
        ScoredSong top = candidates.get(0);
        return FetchResult.of(top.lyrics(), top.title(), top.artist(),
                top.durationSec(), track);
    }

    @Override
    public List<Lyrics.ScoredLyrics> fetchCandidates(TrackInfo track) throws Exception {
        List<Lyrics.ScoredLyrics> scored = new ArrayList<>();
        for (ScoredSong candidate : fetchScoredSongs(track)) {
            scored.add(new Lyrics.ScoredLyrics(candidate.score(), candidate.lyrics()));
        }
        return scored;
    }
}
