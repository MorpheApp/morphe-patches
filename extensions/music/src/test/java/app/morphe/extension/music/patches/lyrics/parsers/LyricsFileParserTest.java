/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4439
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.parsers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import app.morphe.extension.music.patches.lyrics.model.Lyrics;
import app.morphe.extension.music.patches.lyrics.model.LyricsLine;

public class LyricsFileParserTest {

    @Test
    public void parseNullOrEmptyReturnsNull() {
        assertNull(LyricsFileParser.parse(null, "Provider"));
        assertNull(LyricsFileParser.parse("", "Provider"));
    }

    @Test
    public void parseBasicSyncedYaml() {
        String yaml = """
                version: 1.0
                lines:
                  - text: Hello
                    start_ms: 1000
                  - text: World
                    start_ms: 2000
                """;
        Lyrics lyrics = LyricsFileParser.parse(yaml, "TestProvider");
        assertNotNull(lyrics);
        assertEquals(2, lyrics.lines().size());
        assertEquals("Hello", lyrics.lines().get(0).text());
        assertEquals(1000L, lyrics.lines().get(0).startTimeMs());
        assertEquals("World", lyrics.lines().get(1).text());
        assertEquals(2000L, lyrics.lines().get(1).startTimeMs());
        assertTrue(lyrics.synced());
        assertEquals("TestProvider", lyrics.providerName());
    }

    @Test
    public void parseWithMetadata() {
        String yaml = """
                version: 1.0
                metadata:
                  title: Test Song
                  artist: Test Artist
                  album: Test Album
                lines:
                  - text: Hello
                    start_ms: 1000
                    end_ms: 2000
                """;
        Lyrics lyrics = LyricsFileParser.parse(yaml, "TestProvider");
        assertNotNull(lyrics);
        assertEquals(1, lyrics.lines().size());
        assertNotNull(lyrics.credits());
        assertEquals(3, lyrics.credits().size());
        assertTrue(lyrics.credits().contains("title: Test Song"));
        assertTrue(lyrics.credits().contains("artist: Test Artist"));
        assertTrue(lyrics.credits().contains("album: Test Album"));
        assertNull(lyrics.songwriters());
    }

    @Test
    public void parseInstrumentalFlag() {
        String yaml = """
                version: 1.0
                metadata:
                  instrumental: true
                lines:
                  - text: ♪
                    start_ms: 1000
                    end_ms: 2000
                """;
        Lyrics lyrics = LyricsFileParser.parse(yaml, "TestProvider");
        assertNotNull(lyrics);
        assertTrue(lyrics.synced());
    }

    @Test
    public void parsePlainYaml() {
        String yaml = "version: 1.0\nplain: |\n  Hello world\n  This is a test";
        Lyrics lyrics = LyricsFileParser.parse(yaml, "TestProvider");
        assertNotNull(lyrics);
        assertEquals(2, lyrics.lines().size());
        assertEquals("Hello world", lyrics.lines().get(0).text());
        assertEquals("This is a test", lyrics.lines().get(1).text());
        // Plain lyrics are unsynced
        assertFalse(lyrics.synced());
    }

    @Test
    public void parseWithWordTiming() {
        String yaml = """
                version: 1.0
                lines:
                  - text: Hello world
                    start_ms: 1000
                    end_ms: 3000
                    words:
                      - text: Hello
                        start_ms: 1000
                        end_ms: 2000
                      - text: world
                        start_ms: 2000
                        end_ms: 3000
                """;
        Lyrics lyrics = LyricsFileParser.parse(yaml, "TestProvider");
        assertNotNull(lyrics);
        assertEquals(1, lyrics.lines().size());
        LyricsLine line = lyrics.lines().get(0);
        assertEquals("Hello world", line.text());
        assertTrue(line.hasWordTimings());
    }

    @Test
    public void parseWithSourceUrl() {
        String yaml = """
                version: 1.0
                lines:
                  - text: Hello
                    start_ms: 1000
                    end_ms: 2000
                """;
        Lyrics lyrics = LyricsFileParser.parse(yaml, "TestProvider", "https://example.com");
        assertNotNull(lyrics);
        assertEquals("https://example.com", lyrics.sourceUrl());
    }

    @Test
    public void parseEmptyLinesReturnsNotFound() {
        String yaml = """
                version: 1.0
                lines: []
                """;
        Lyrics lyrics = LyricsFileParser.parse(yaml, "TestProvider");
        assertNotNull(lyrics);
        assertEquals(Lyrics.NOT_FOUND, lyrics);
    }

    @Test
    public void parseNoMetadataCreditsNull() {
        String yaml = """
                version: 1.0
                lines:
                  - text: Hello
                    start_ms: 1000
                    end_ms: 2000
                """;
        Lyrics lyrics = LyricsFileParser.parse(yaml, "TestProvider");
        assertNotNull(lyrics);
        // No metadata, so songwriters and credits should be null
        assertNull(lyrics.songwriters());
        assertNull(lyrics.credits());
    }
}
