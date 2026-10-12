/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics;

import androidx.annotation.Nullable;

import app.morphe.extension.music.patches.lyrics.model.MetadataCleaner;
import app.morphe.extension.music.patches.lyrics.model.TrackInfo;

/**
 * Turns a raw title and artist pair, as a queue item carries them, into the track a
 * lyrics lookup is built from.
 */
final class QueueTracks {

    private QueueTracks() {
    }

    /**
     * Cleaned exactly like the metadata of a track event, so a queue item and a track
     * event describing the same song reach the providers as one track. Null when the two
     * strings do not describe one.
     */
    @Nullable
    static TrackInfo trackOf(@Nullable String rawTitle, @Nullable String rawArtist,
                             int durationSeconds, @Nullable String album) {
        if (rawTitle == null || rawTitle.trim().isEmpty()
                || rawArtist == null || rawArtist.trim().isEmpty()) {
            return null;
        }
        String[] parsed = MetadataCleaner.parseCleanTitleAndArtist(rawTitle, rawArtist);
        TrackInfo track = new TrackInfo(parsed[1], parsed[0],
                album != null ? album : "", durationSeconds);
        return (track.title().isEmpty() || track.artist().isEmpty()) ? null : track;
    }
}