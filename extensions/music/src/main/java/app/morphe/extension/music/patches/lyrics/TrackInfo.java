/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2269
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;
import java.util.Objects;

/**
 * Normalized track metadata used to look lyrics up.
 *
 * @param durationSeconds Track duration in seconds, or 0 if unknown.
 */
public record TrackInfo(String title, String artist, String album, int durationSeconds) {

    /**
     * Key used for caching. Duration is left out so that a metadata update
     * that only corrects the duration still hits the cache.
     */
    public String cacheKey() {
        return artist.toLowerCase(Locale.ROOT) + " " + title.toLowerCase(Locale.ROOT);
    }

    /**
     * Only the title and the artist identify a track. The album and the duration
     * are left out because the app reports them late, and a metadata update that
     * fills them in must not read as a track change.
     */
    @Override
    public boolean equals(@Nullable Object other) {
        if (this == other) return true;
        if (!(other instanceof TrackInfo that)) return false;
        return title.equals(that.title) && artist.equals(that.artist);
    }

    @Override
    public int hashCode() {
        return Objects.hash(title, artist);
    }

    @NonNull
    @Override
    public String toString() {
        return artist + " - " + title + " (" + durationSeconds + "s)";
    }
}
