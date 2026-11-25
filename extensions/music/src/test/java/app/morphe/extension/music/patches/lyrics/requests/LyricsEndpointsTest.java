/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.requests;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class LyricsEndpointsTest {

    private static final String OFFICIAL = "https://official.example/api/";

    @Test
    public void blankKeepsOfficial() {
        assertEquals("https://official.example/api", LyricsEndpoints.base(OFFICIAL, ""));
        assertEquals("https://official.example/api", LyricsEndpoints.base(OFFICIAL, null));
        assertEquals("https://official.example/api", LyricsEndpoints.base(OFFICIAL, "   "));
    }

    @Test
    public void httpOverrideReplacesOfficial() {
        assertEquals("http://localhost:8080/api",
                LyricsEndpoints.base(OFFICIAL, " http://localhost:8080/api/ "));
        assertEquals("https://self.example",
                LyricsEndpoints.base(OFFICIAL, "https://self.example"));
    }

    @Test
    public void invalidOverrideKeepsOfficial() {
        assertEquals("https://official.example/api", LyricsEndpoints.base(OFFICIAL, "localhost:8080"));
        assertEquals("https://official.example/api", LyricsEndpoints.base(OFFICIAL, "ftp://self.example"));
        assertEquals("https://official.example/api", LyricsEndpoints.base(OFFICIAL, "https://"));
        assertEquals("https://official.example/api", LyricsEndpoints.base(OFFICIAL, "not a url"));
    }

    @Test
    public void directoryHasOneTrailingSlash() {
        assertEquals("https://self.example/api/",
                LyricsEndpoints.directory(OFFICIAL, "https://self.example/api"));
        assertEquals("https://self.example/api/",
                LyricsEndpoints.directory(OFFICIAL, "https://self.example/api///"));
    }

    @Test
    public void hostsReplacesMirrorList() {
        String[] official = {"https://a.example", "https://b.example"};
        assertArrayEquals(new String[]{"https://self.example"},
                LyricsEndpoints.hosts(official, "https://self.example/"));
        assertArrayEquals(official, LyricsEndpoints.hosts(official, ""));
    }
}
