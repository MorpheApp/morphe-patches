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

    /**
     * Fetches lyrics. Always called off the main thread.
     *
     * @return Lyrics, or {@code null} if this provider has none for the track.
     */
    @Nullable
    Lyrics fetch(TrackInfo track) throws Exception;

    /**
     * Returns all candidate lyrics for the track, ordered by relevance.
     * The first candidate is typically the best match.
     */
    default List<Lyrics> fetchCandidates(TrackInfo track) throws Exception {
        Lyrics single = fetch(track);
        return (single != null) ? Collections.singletonList(single) : Collections.emptyList();
    }

    /**
     * Whether this provider supports multiple candidates per track.
     */
    default boolean hasCandidates() {
        return false;
    }
}
