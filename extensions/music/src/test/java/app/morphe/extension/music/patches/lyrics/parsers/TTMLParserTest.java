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
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import app.morphe.extension.music.patches.lyrics.model.Lyrics;

public class TTMLParserTest {

    @Test
    public void parseTimeMmSsMmmFormat() {
        assertEquals(1000L, TTMLParser.parseTime("00:01.000"));
        assertEquals(62030L, TTMLParser.parseTime("01:02.030"));
        assertEquals(0L, TTMLParser.parseTime("00:00.000"));
    }

    @Test
    public void parseTimeMmSsFormat() {
        assertEquals(1000L, TTMLParser.parseTime("00:01"));
        assertEquals(62000L, TTMLParser.parseTime("01:02"));
    }

    @Test
    public void parseTimeSecondsFormat() {
        assertEquals(1000L, TTMLParser.parseTime("1s"));
        assertEquals(60000L, TTMLParser.parseTime("60s"));
    }

    @Test
    public void parseTimeMillisFormat() {
        assertEquals(1000L, TTMLParser.parseTime("1000ms"));
        assertEquals(500L, TTMLParser.parseTime("500ms"));
    }

    @Test
    public void parseTimeMinutesFormat() {
        assertEquals(60000L, TTMLParser.parseTime("1m"));
    }

    @Test
    public void parseTimeHoursFormat() {
        assertEquals(3600000L, TTMLParser.parseTime("1h"));
    }

    @Test
    public void parseTimeInvalidReturnsZero() {
        assertEquals(0L, TTMLParser.parseTime(null));
        assertEquals(0L, TTMLParser.parseTime(""));
        assertEquals(0L, TTMLParser.parseTime("invalid"));
    }

    @Test
    public void isContinuousCjkWithHan() {
        assertTrue(TTMLParser.isContinuousCjk("你好世界"));
    }

    @Test
    public void isContinuousCjkWithKana() {
        assertTrue(TTMLParser.isContinuousCjk("こんにちは"));
    }

    @Test
    public void isContinuousCjkWithHangul() {
        // Hangul is NOT included in isContinuousCjk - only Han and Kana
        assertFalse(TTMLParser.isContinuousCjk("안녕하세요"));
    }

    @Test
    public void isContinuousCjkWithLatin() {
        assertFalse(TTMLParser.isContinuousCjk("Hello world"));
    }

    @Test
    public void isContinuousCjkWithMixed() {
        // Only the last code point matters: "Hello 你好" ends with Han, "你好 Hello" with Latin.
        assertTrue(TTMLParser.isContinuousCjk("Hello 你好"));
        assertFalse(TTMLParser.isContinuousCjk("你好 Hello"));
    }

    @Test
    public void isContinuousCjkNull() {
        assertFalse(TTMLParser.isContinuousCjk(null));
    }

    @Test
    public void isContinuousCjkEmpty() {
        assertFalse(TTMLParser.isContinuousCjk(""));
    }

    @Test
    public void isContinuousCjkPunctuationOnly() {
        assertFalse(TTMLParser.isContinuousCjk("!?."));
    }

    @Test
    public void isContinuousCjkSingleCjkChar() {
        assertTrue(TTMLParser.isContinuousCjk("字"));
    }

    @Test
    public void thaiLineWithoutSpacesIsNotReconstructed() {
        // A Thai line whose spans have no whitespace must keep its text as-is,
        // not be rebuilt with a space between every word.
        String ttml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<tt xmlns=\"http://www.w3.org/ns/ttml\">"
                + "<head></head><body><div><p begin=\"00:01.000\" end=\"00:02.000\">"
                + "<span begin=\"00:01.000\" end=\"00:01.200\">\u0e40\u0e14\u0e35\u0e48\u0e22\u0e27</span>"
                + "<span begin=\"00:01.200\" end=\"00:01.400\">\u0e19\u0e35\u0e49</span>"
                + "<span begin=\"00:01.400\" end=\"00:01.600\">\u0e44\u0e21\u0e48</span>"
                + "<span begin=\"00:01.600\" end=\"00:01.800\">\u0e15\u0e2d\u0e1a</span>"
                + "<span begin=\"00:01.800\" end=\"00:02.000\">\u0e19\u0e30</span>"
                + "</p></div></body></tt>";
        Lyrics lyrics = TTMLParser.ttmlToLyrics(ttml, "TestProvider", null);
        assertNotNull(lyrics);
        assertEquals(1, lyrics.lines().size());
        assertEquals("\u0e40\u0e14\u0e35\u0e48\u0e22\u0e27\u0e19\u0e35\u0e49\u0e44\u0e21\u0e48\u0e15\u0e2d\u0e1a\u0e19\u0e30",
                lyrics.lines().get(0).text());
        for (var word : lyrics.lines().get(0).words()) {
            assertFalse("No Thai word should be marked endsWithSpace", word.endsWithSpace());
        }
    }

    @Test
    public void thaiLineWithSpaceKeepsOnlyThatSpace() {
        // A space in the source is the only one that should survive.
        String ttml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<tt xmlns=\"http://www.w3.org/ns/ttml\">"
                + "<head></head><body><div><p begin=\"00:01.000\" end=\"00:02.000\">"
                + "<span begin=\"00:01.000\" end=\"00:01.200\">\u0e17\u0e31\u0e01</span>"
                + "<span begin=\"00:01.200\" end=\"00:01.400\">\u0e44\u0e1b</span>"
                + "<span begin=\"00:01.400\" end=\"00:01.600\">\u0e41\u0e25\u0e49\u0e27</span> "
                + "<span begin=\"00:01.600\" end=\"00:01.800\">\u0e44\u0e21\u0e48</span>"
                + "<span begin=\"00:01.800\" end=\"00:02.000\">\u0e15\u0e2d\u0e1a</span>"
                + "</p></div></body></tt>";
        Lyrics lyrics = TTMLParser.ttmlToLyrics(ttml, "TestProvider", null);
        assertNotNull(lyrics);
        assertEquals(1, lyrics.lines().size());
        assertEquals("\u0e17\u0e31\u0e01\u0e44\u0e1b\u0e41\u0e25\u0e49\u0e27 \u0e44\u0e21\u0e48\u0e15\u0e2d\u0e1a",
                lyrics.lines().get(0).text());
        var words = lyrics.lines().get(0).words();
        assertEquals(5, words.size());
        assertFalse(words.get(0).endsWithSpace());
        assertFalse(words.get(1).endsWithSpace());
        assertTrue(words.get(2).endsWithSpace());
        assertFalse(words.get(3).endsWithSpace());
        assertFalse(words.get(4).endsWithSpace());
    }

    @Test
    public void audioLyricOffsetTagIsBakedIntoTimestamps() {
        String ttml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<tt xmlns=\"http://www.w3.org/ns/ttml\""
                + " xmlns:itunes=\"http://music.apple.com/lyric-ttml-internal\">"
                + "<head><iTunesMetadata><audio lyricOffset=\"0.940\" role=\"base\"/></iTunesMetadata></head>"
                + "<body><div><p begin=\"00:01.000\" end=\"00:01.500\">Hello</p></div></body>"
                + "</tt>";
        Lyrics lyrics = TTMLParser.ttmlToLyrics(ttml, "TestProvider", null);
        assertNotNull(lyrics);
        assertEquals(1, lyrics.lines().size());
        assertEquals(1940L, lyrics.lines().get(0).startTimeMs());
        assertEquals(2440L, lyrics.lines().get(0).endTimeMs());
    }

    @Test
    public void noAudioTagLeavesTimestampsUnchanged() {
        String ttml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<tt xmlns=\"http://www.w3.org/ns/ttml\">"
                + "<head></head><body><div><p begin=\"00:01.000\" end=\"00:01.500\">Hello</p></div></body>"
                + "</tt>";
        Lyrics lyrics = TTMLParser.ttmlToLyrics(ttml, "TestProvider", null);
        assertNotNull(lyrics);
        assertEquals(1000L, lyrics.lines().get(0).startTimeMs());
        assertEquals(1500L, lyrics.lines().get(0).endTimeMs());
    }

    @Test
    public void negativeLyricOffsetShiftsTimestampsBackward() {
        String ttml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<tt xmlns=\"http://www.w3.org/ns/ttml\""
                + " xmlns:itunes=\"http://music.apple.com/lyric-ttml-internal\">"
                + "<head><iTunesMetadata><audio lyricOffset=\"-0.500\" role=\"base\"/></iTunesMetadata></head>"
                + "<body><div><p begin=\"00:01.000\" end=\"00:01.500\">Hello</p></div></body>"
                + "</tt>";
        Lyrics lyrics = TTMLParser.ttmlToLyrics(ttml, "TestProvider", null);
        assertNotNull(lyrics);
        assertEquals(500L, lyrics.lines().get(0).startTimeMs());
        assertEquals(1000L, lyrics.lines().get(0).endTimeMs());
    }
}
