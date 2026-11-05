/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4451
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.soundproof.domain;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Read-only index of the SoundProof database, keyed by artist id.
 */
public final class ArtistDirectory {

    /** Holds the whole database, so it is shared between copies and never rebuilt. */
    private final Map<String, ArtistRecord> artistsById;
    /** Win over {@link #artistsById}. */
    private final Map<String, ArtistRecord> replacementsById;

    public ArtistDirectory(Collection<ArtistRecord> artists) {
        this(indexById(artists), Collections.emptyMap());
    }

    private ArtistDirectory(Map<String, ArtistRecord> artistsById, Map<String, ArtistRecord> replacementsById) {
        this.artistsById = artistsById;
        this.replacementsById = replacementsById;
    }

    private static Map<String, ArtistRecord> indexById(Collection<ArtistRecord> artists) {
        Map<String, ArtistRecord> index = new HashMap<>(artists.size() * 2);
        for (ArtistRecord artist : artists) {
            index.put(artist.id(), artist);
        }
        return index;
    }

    public Optional<ArtistRecord> findByName(String artistName) {
        String artistId = ArtistNameNormalizer.toArtistId(artistName);
        ArtistRecord replacement = replacementsById.get(artistId);
        return Optional.ofNullable(replacement != null ? replacement : artistsById.get(artistId));
    }

    /**
     * @return A copy where the given artists replace the entries with the same id, or are added.
     */
    public ArtistDirectory withArtists(Collection<ArtistRecord> replacements) {
        if (replacements.isEmpty()) {
            return this;
        }
        Map<String, ArtistRecord> merged = new HashMap<>(replacementsById);
        merged.putAll(indexById(replacements));
        return new ArtistDirectory(artistsById, merged);
    }

    public int size() {
        int size = artistsById.size();
        for (String artistId : replacementsById.keySet()) {
            if (!artistsById.containsKey(artistId)) {
                size++;
            }
        }
        return size;
    }
}
