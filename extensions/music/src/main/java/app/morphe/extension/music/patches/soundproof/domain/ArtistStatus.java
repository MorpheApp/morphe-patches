/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4451
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.soundproof.domain;

public enum ArtistStatus {
    /** Listed in the database and at or above the user's AI threshold: the track is skipped. */
    AI,
    /** Listed in the database but below the user's AI threshold. */
    HUMAN,
    /** The database is loaded but does not list the artist. */
    UNKNOWN,
    /** No database is available yet, so nothing can be decided. */
    UNAVAILABLE
}
