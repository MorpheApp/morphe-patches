/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4439
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

public class TrackInfoTest {

    @Test
    public void cacheKeyUsesLowercaseArtistAndTitle() {
        TrackInfo track = new TrackInfo("My Title", "My Artist", null, 0);
        assertEquals("my artist my title", track.cacheKey());
    }

    @Test
    public void equalsIgnoresAlbumAndDuration() {
        TrackInfo a = new TrackInfo("Title", "Artist", "AlbumA", 100);
        TrackInfo b = new TrackInfo("Title", "Artist", "AlbumB", 200);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    public void equalsDifferentTitleNotEqual() {
        assertNotEquals(new TrackInfo("TitleA", "Artist", null, 0),
                new TrackInfo("TitleB", "Artist", null, 0));
    }

    @Test
    public void equalsDifferentArtistNotEqual() {
        assertNotEquals(new TrackInfo("Title", "ArtistA", null, 0),
                new TrackInfo("Title", "ArtistB", null, 0));
    }

    @Test
    public void equalsSelf() {
        TrackInfo track = new TrackInfo("Title", "Artist", null, 0);
        assertEquals(track, track);
    }

    @Test
    public void equalsNullFalse() {
        TrackInfo track = new TrackInfo("Title", "Artist", null, 0);
        assertNotEquals(track, null);
    }

    @Test
    public void equalsNonTrackFalse() {
        TrackInfo track = new TrackInfo("Title", "Artist", null, 0);
        assertNotEquals(track, "not a track");
    }

    @Test
    public void toStringFormatsAsArtistDashTitleDuration() {
        TrackInfo track = new TrackInfo("Title", "Artist", null, 180);
        assertEquals("Artist - Title (180s)", track.toString());
    }

    @Test
    public void hashCodeConsistent() {
        TrackInfo track = new TrackInfo("Title", "Artist", null, 0);
        assertEquals(track.hashCode(), track.hashCode());
    }
}
