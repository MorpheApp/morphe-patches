/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Before;
import org.junit.Test;

import app.morphe.extension.music.patches.lyrics.model.TrackInfo;

public class QueueTracksTest {

    @Before
    public void setUp() {
        LyricsTestEnv.bootstrap();
    }

    @Test
    public void trackOfNullTitleReturnsNull() {
        assertNull(QueueTracks.trackOf(null, "Artist"));
    }

    @Test
    public void trackOfNullArtistReturnsNull() {
        assertNull(QueueTracks.trackOf("Title", null));
    }

    @Test
    public void trackOfNullBothReturnsNull() {
        assertNull(QueueTracks.trackOf(null, null));
    }

    @Test
    public void trackOfEmptyTitleReturnsNull() {
        assertNull(QueueTracks.trackOf("", "Artist"));
    }

    @Test
    public void trackOfEmptyArtistReturnsNull() {
        assertNull(QueueTracks.trackOf("Title", ""));
    }

    @Test
    public void trackOfWhitespaceOnlyReturnsNull() {
        assertNull(QueueTracks.trackOf("   ", "   "));
    }

    @Test
    public void trackOfValidInputsReturnsTrackInfo() {
        TrackInfo result = QueueTracks.trackOf("Song Title", "Artist Name");
        assertNotNull(result);
        assertEquals("Song Title", result.title());
        assertEquals("Artist Name", result.artist());
    }

    @Test
    public void trackOfTrimmedInputsReturnsCleanedTrackInfo() {
        TrackInfo result = QueueTracks.trackOf("  Song Title  ", "  Artist Name  ");
        assertNotNull(result);
        assertEquals("Song Title", result.title());
        assertEquals("Artist Name", result.artist());
    }

    @Test
    public void trackOfTitleWithVariationReturnsCleanedTitle() {
        // Titles with {v} or parenthetical variations are cleaned by MetadataCleaner
        TrackInfo result = QueueTracks.trackOf("Song (Remastered)", "Artist");
        assertNotNull(result);
        assertEquals("Song", result.title());
        assertEquals("Artist", result.artist());
    }

    @Test
    public void trackOfTitleWithVariationAndTrailingArtistReturnsCleanedTrack() {
        TrackInfo result = QueueTracks.trackOf("Song (Remastered) - Remastered Version", "Artist (feat. Someone)");
        assertNotNull(result);
        // The regex strips (Remastered) from parentheses, but " - Remastered Version" after the dash
        // is not matched because the pattern only matches variations in parentheses or " - Topic" at the end.
        assertEquals("Song - Remastered Version", result.title());
        // "feat." inside parentheses doesn't match the " feat. " separator (which requires spaces),
        // so the artist is not split and remains "Artist (feat. Someone)".
        assertEquals("Artist (feat. Someone)", result.artist());
    }
}
