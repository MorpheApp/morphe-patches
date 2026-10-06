/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2269
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.model;

import androidx.annotation.Nullable;

import java.util.List;

/**
 * What the user picked for one track: the lyric shown first, the order the remaining
 * candidates were left in, and the custom search terms that produced them.
 *
 * @param queryTitle   custom title search terms, or null when the default terms were used
 * @param queryArtist  custom artist search terms, or null when the default terms were used
 * @param preferred    the lyric to show first on the next playback of the track
 * @param queue        fingerprints of the remaining candidates, in the order they were left in
 * @param fingerprint  fingerprint the preferred lyric had before it was written to disk, or
 *                     null when it was never computed. Writing the lines back drops the raw
 *                     text the fingerprint was taken from, so it cannot be taken again.
 */
public record LyricsPreference(@Nullable String queryTitle,
                        @Nullable String queryArtist,
                        Lyrics preferred,
                        List<String> queue,
                        @Nullable String fingerprint) {
}
