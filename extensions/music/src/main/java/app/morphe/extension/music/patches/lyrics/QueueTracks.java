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

/** Builds the {@link TrackInfo} a lookup runs on from a queue item's title and artist. */
final class QueueTracks {

    private QueueTracks() {
    }

    /**
     * Cleaned as a track event's metadata is, so both reach the providers as one track.
     * Null when the strings do not describe one.
     */
    @Nullable
    static TrackInfo trackOf(@Nullable String rawTitle, @Nullable String rawArtist) {
        if (rawTitle == null || rawTitle.trim().isEmpty()
                || rawArtist == null || rawArtist.trim().isEmpty()) {
            return null;
        }
        String[] parsed = MetadataCleaner.parseCleanTitleAndArtist(rawTitle, rawArtist);
        TrackInfo track = new TrackInfo(parsed[1], parsed[0], "", 0);
        return (track.title().isEmpty() || track.artist().isEmpty()) ? null : track;
    }
}
