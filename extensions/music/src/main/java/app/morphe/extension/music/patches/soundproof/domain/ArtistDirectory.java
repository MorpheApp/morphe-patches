/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.music.patches.soundproof.domain;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Read-only index of the SoundProof database, keyed by artist id.
 */
public final class ArtistDirectory {

    private final Map<String, ArtistRecord> artistsById;

    public ArtistDirectory(Collection<ArtistRecord> artists) {
        Objects.requireNonNull(artists);
        Map<String, ArtistRecord> index = new HashMap<>(artists.size() * 2);
        for (ArtistRecord artist : artists) {
            index.put(artist.id(), artist);
        }
        this.artistsById = Collections.unmodifiableMap(index);
    }

    public Optional<ArtistRecord> findByName(String artistName) {
        String artistId = ArtistNameNormalizer.toArtistId(artistName);
        return Optional.ofNullable(artistsById.get(artistId));
    }

    public int size() {
        return artistsById.size();
    }
}
