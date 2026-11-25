/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2625
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.requests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import app.morphe.extension.music.patches.lyrics.model.TrackInfo;

public class LyricsPlusProviderStaticTest {

    private static TrackInfo track(String title, String artist, String album, int duration) {
        return new TrackInfo(title, artist, album, duration);
    }

    @Test
    public void officialHostIsFirst() {
        assertEquals("https://lyricsplus.prjktla.my.id", LyricsPlusProvider.API_HOSTS[0]);
    }

    @Test
    public void binimumMirrorIsListed() {
        boolean found = false;
        for (String host : LyricsPlusProvider.API_HOSTS) {
            if ("https://lyricsplus.binimum.org".equals(host)) {
                found = true;
                break;
            }
        }
        assertTrue(found);
    }

    @Test
    public void buildUrlEncodesSpacesAsPercent20() throws Exception {
        String url = LyricsPlusProvider.buildUrl("https://lyricsplus.prjktla.my.id",
                track("Shape of You", "Ed Sheeran", "", 233));
        assertTrue(url.contains("title=Shape%20of%20You"));
        assertTrue(url.contains("artist=Ed%20Sheeran"));
        assertFalse(url.contains("+"));
    }

    @Test
    public void buildUrlOmitsAlbumWhenEmpty() throws Exception {
        String url = LyricsPlusProvider.buildUrl("https://lyricsplus.prjktla.my.id",
                track("Hello", "Adele", "", 295));
        assertFalse(url.contains("album="));
    }

    @Test
    public void buildUrlIncludesAlbumWhenPresent() throws Exception {
        String url = LyricsPlusProvider.buildUrl("https://lyricsplus.prjktla.my.id",
                track("Hello", "Adele", "25", 295));
        assertTrue(url.contains("&album=25&"));
    }

    @Test
    public void buildUrlOmitsDurationWhenZero() throws Exception {
        String url = LyricsPlusProvider.buildUrl("https://lyricsplus.prjktla.my.id",
                track("Hello", "Adele", "25", 0));
        assertFalse(url.contains("duration="));
    }

    @Test
    public void buildUrlTrimsTrailingSlashFromHost() throws Exception {
        String url = LyricsPlusProvider.buildUrl("https://lyricsplus.binimum.org/",
                track("Hello", "Adele", "25", 295));
        assertTrue(url.startsWith("https://lyricsplus.binimum.org/v1/ttml/get"));
    }
}
