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
 * What the user picked for one track.
 *
 * @param queryTitle   custom title search terms, or null when the default terms were used
 * @param queryArtist  custom artist search terms, or null when the default terms were used
 * @param preferred    the lyric to show first next time
 * @param queue        fingerprints of the rest, in order
 * @param fingerprint  fingerprint before the lines were written, which drops the raw text it
 *                     was taken from. Null when it was never computed.
 * @param videoId      the video the lyric was picked for, or null when it was written without
 *                     one, which is the case for the entries meant to answer any video.
 */
public record LyricsPreference(@Nullable String queryTitle,
                        @Nullable String queryArtist,
                        Lyrics preferred,
                        List<String> queue,
                        @Nullable String fingerprint,
                        @Nullable String trackKey,
                        @Nullable String videoId) {
}
