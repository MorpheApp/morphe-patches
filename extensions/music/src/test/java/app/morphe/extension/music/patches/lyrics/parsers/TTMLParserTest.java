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
                + "<span begin=\"00:01.000\" end=\"00:01.200\">เดี่ยว</span>"
                + "<span begin=\"00:01.200\" end=\"00:01.400\">นี้</span>"
                + "<span begin=\"00:01.400\" end=\"00:01.600\">ไม่</span>"
                + "<span begin=\"00:01.600\" end=\"00:01.800\">ตอบ</span>"
                + "<span begin=\"00:01.800\" end=\"00:02.000\">นะ</span>"
                + "</p></div></body></tt>";
        Lyrics lyrics = TTMLParser.ttmlToLyrics(ttml, "TestProvider", null);
        assertNotNull(lyrics);
        assertEquals(1, lyrics.lines().size());
        assertEquals("เดี่ยวนี้ไม่ตอบนะ",
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
                + "<span begin=\"00:01.000\" end=\"00:01.200\">ทัก</span>"
                + "<span begin=\"00:01.200\" end=\"00:01.400\">ไป</span>"
                + "<span begin=\"00:01.400\" end=\"00:01.600\">แล้ว</span> "
                + "<span begin=\"00:01.600\" end=\"00:01.800\">ไม่</span>"
                + "<span begin=\"00:01.800\" end=\"00:02.000\">ตอบ</span>"
                + "</p></div></body></tt>";
        Lyrics lyrics = TTMLParser.ttmlToLyrics(ttml, "TestProvider", null);
        assertNotNull(lyrics);
        assertEquals(1, lyrics.lines().size());
        assertEquals("ทักไปแล้ว ไม่ตอบ",
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

    private static String ttmlWithBody(String body) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<tt xmlns=\"http://www.w3.org/ns/ttml\""
                + " xmlns:itunes=\"http://music.apple.com/lyric-ttml-internal\">"
                + "<head></head><body>" + body + "</body></tt>";
    }

    @Test
    public void emptyDivSongPartFallsBackToHyphenatedAttribute() {
        String ttml = ttmlWithBody("<div itunes:songPart=\"\" itunes:song-part=\"Chorus\">"
                + "<p begin=\"00:01.000\" end=\"00:02.000\">Hello</p></div>");
        Lyrics lyrics = TTMLParser.ttmlToLyrics(ttml, "TestProvider", null);
        assertNotNull(lyrics);
        assertEquals(1, lyrics.lines().size());
        assertEquals("Chorus", lyrics.lines().get(0).songPart());
    }

    @Test
    public void nestedDivWithoutSongPartKeepsOuterLabel() {
        String ttml = ttmlWithBody("<div itunes:songPart=\"Verse\">"
                + "<p begin=\"00:01.000\" end=\"00:02.000\">One</p>"
                + "<div><p begin=\"00:02.000\" end=\"00:03.000\">Two</p></div>"
                + "<p begin=\"00:03.000\" end=\"00:04.000\">Three</p></div>");
        Lyrics lyrics = TTMLParser.ttmlToLyrics(ttml, "TestProvider", null);
        assertNotNull(lyrics);
        assertEquals(3, lyrics.lines().size());
        assertEquals("Verse", lyrics.lines().get(0).songPart());
        assertEquals("Verse", lyrics.lines().get(1).songPart());
        assertEquals("Verse", lyrics.lines().get(2).songPart());
    }

    @Test
    public void paragraphSongPartIsUsedWhenDivHasNone() {
        String ttml = ttmlWithBody("<div><p itunes:songPart=\"Bridge\""
                + " begin=\"00:01.000\" end=\"00:02.000\">Hello</p></div>");
        Lyrics lyrics = TTMLParser.ttmlToLyrics(ttml, "TestProvider", null);
        assertNotNull(lyrics);
        assertEquals(1, lyrics.lines().size());
        assertEquals("Bridge", lyrics.lines().get(0).songPart());
    }

    @Test
    public void paragraphSongPartOverridesDiv() {
        String ttml = ttmlWithBody("<div itunes:songPart=\"Verse\"><p itunes:songPart=\"Bridge\""
                + " begin=\"00:01.000\" end=\"00:02.000\">Hello</p></div>");
        Lyrics lyrics = TTMLParser.ttmlToLyrics(ttml, "TestProvider", null);
        assertNotNull(lyrics);
        assertEquals("Bridge", lyrics.lines().get(0).songPart());
    }

    @Test
    public void sequentialDivsResetSongPart() {
        String ttml = ttmlWithBody("<div itunes:songPart=\"Verse\">"
                + "<p begin=\"00:01.000\" end=\"00:02.000\">One</p></div>"
                + "<div itunes:songPart=\"Chorus\">"
                + "<p begin=\"00:02.000\" end=\"00:03.000\">Two</p></div>"
                + "<div><p begin=\"00:03.000\" end=\"00:04.000\">Three</p></div>");
        Lyrics lyrics = TTMLParser.ttmlToLyrics(ttml, "TestProvider", null);
        assertNotNull(lyrics);
        assertEquals(3, lyrics.lines().size());
        assertEquals("Verse", lyrics.lines().get(0).songPart());
        assertEquals("Chorus", lyrics.lines().get(1).songPart());
        assertNull(lyrics.lines().get(2).songPart());
    }

    @Test
    public void sidecarTransliterationPreservesSpacesBetweenSpans() {
        String ttml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<tt xmlns=\"http://www.w3.org/ns/ttml\""
                + " xmlns:itunes=\"http://music.apple.com/lyric-ttml-internal\">"
                + "<head><iTunesMetadata><transliterations>"
                + "<transliteration xml:lang=\"ja-Latn\">"
                + "<text for=\"L1\">"
                + "<span begin=\"00:01.000\" end=\"00:01.500\">By my side </span>"
                + "<span begin=\"00:01.500\" end=\"00:02.000\">kimi</span> "
                + "<span begin=\"00:02.000\" end=\"00:02.500\">to waraitai</span>"
                + "</text>"
                + "</transliteration></transliterations></iTunesMetadata></head>"
                + "<body><div><p itunes:key=\"L1\" begin=\"00:01.000\" end=\"00:02.500\">"
                + "<span begin=\"00:01.000\" end=\"00:01.500\">By my side </span>"
                + "<span begin=\"00:01.500\" end=\"00:02.000\">君と</span>"
                + "<span begin=\"00:02.000\" end=\"00:02.500\">笑いたい</span>"
                + "</p></div></body></tt>";
        Lyrics lyrics = TTMLParser.ttmlToLyrics(ttml, "TestProvider", null);
        assertNotNull(lyrics);
        assertNotNull(lyrics.romanization());
        assertEquals(1, lyrics.romanization().size());
        assertEquals("By my side kimi to waraitai", lyrics.romanization().get(0).text());
    }

    @Test
    public void partialSidecarAlignmentLeavesWordsCleanForWholeLineMode() {
        String ttml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<tt xmlns=\"http://www.w3.org/ns/ttml\""
                + " xmlns:itunes=\"http://music.apple.com/lyric-ttml-internal\">"
                + "<head><iTunesMetadata><transliterations>"
                + "<transliteration xml:lang=\"ja-Latn\">"
                + "<text for=\"L1\">"
                + "<span begin=\"00:01.500\" end=\"00:02.000\">kimi ga fuchidotta ai no kaotsuki</span>"
                + "</text>"
                + "</transliteration></transliterations></iTunesMetadata></head>"
                + "<body><div><p itunes:key=\"L1\" begin=\"00:01.000\" end=\"00:03.000\">"
                + "<span begin=\"00:01.000\" end=\"00:01.500\">君が</span>"
                + "<span begin=\"00:01.500\" end=\"00:02.000\">縁取</span>"
                + "<span begin=\"00:02.000\" end=\"00:03.000\">った愛の顔つき</span>"
                + "</p></div></body></tt>";
        Lyrics lyrics = TTMLParser.ttmlToLyrics(ttml, "TestProvider", null);
        assertNotNull(lyrics);
        assertNotNull(lyrics.romanization());
        assertEquals("kimi ga fuchidotta ai no kaotsuki", lyrics.romanization().get(0).text());
        for (app.morphe.extension.music.patches.lyrics.model.Word w : lyrics.lines().get(0).words()) {
            assertNull(w.romaji());
        }
    }

    @Test
    public void sidecarTransliterationPreservesLeadingSpaceBeforeMixedTextNode() {
        String ttml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<tt xmlns=\"http://www.w3.org/ns/ttml\""
                + " xmlns:itunes=\"http://music.apple.com/lyric-ttml-internal\">"
                + "<head><iTunesMetadata><transliterations>"
                + "<transliteration xml:lang=\"ja-Latn\">"
                + "<text for=\"L1\">"
                + "<span begin=\"00:01.000\" end=\"00:01.500\">kimi</span>"
                + " no "
                + "<span begin=\"00:02.000\" end=\"00:02.500\">na</span>"
                + "</text>"
                + "</transliteration></transliterations></iTunesMetadata></head>"
                + "<body><div><p itunes:key=\"L1\" begin=\"00:01.000\" end=\"00:02.500\">"
                + "<span begin=\"00:01.000\" end=\"00:01.500\">君</span>"
                + "<span begin=\"00:01.500\" end=\"00:02.000\">の</span>"
                + "<span begin=\"00:02.000\" end=\"00:02.500\">名</span>"
                + "</p></div></body></tt>";
        Lyrics lyrics = TTMLParser.ttmlToLyrics(ttml, "TestProvider", null);
        assertNotNull(lyrics);
        assertNotNull(lyrics.romanization());
        assertEquals("kimi no na", lyrics.romanization().get(0).text());
    }

    @Test
    public void timedSidecarWithEqualSpanCountDoesNotForceWrongIndexPairing() {
        String ttml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<tt xmlns=\"http://www.w3.org/ns/ttml\""
                + " xmlns:itunes=\"http://music.apple.com/lyric-ttml-internal\">"
                + "<head><iTunesMetadata><transliterations>"
                + "<transliteration xml:lang=\"ja-Latn\">"
                + "<text for=\"L1\">"
                + "<span begin=\"00:01.000\" end=\"00:01.250\">kimi</span> "
                + "<span begin=\"00:01.250\" end=\"00:01.500\">wo</span> "
                + "<span begin=\"00:01.500\" end=\"00:02.000\">omotte</span> "
                + "<span begin=\"00:02.000\" end=\"00:02.500\">iru</span>"
                + "</text>"
                + "</transliteration></transliterations></iTunesMetadata></head>"
                + "<body><div><p itunes:key=\"L1\" begin=\"00:01.000\" end=\"00:02.500\">"
                + "<span begin=\"00:01.000\" end=\"00:01.500\">君を</span>"
                + "<span begin=\"00:01.500\" end=\"00:01.800\">想</span>"
                + "<span begin=\"00:01.800\" end=\"00:02.100\">って</span>"
                + "<span begin=\"00:02.100\" end=\"00:02.500\">いる</span>"
                + "</p></div></body></tt>";
        Lyrics lyrics = TTMLParser.ttmlToLyrics(ttml, "TestProvider", null);
        assertNotNull(lyrics);
        assertNotNull(lyrics.romanization());
        assertEquals("kimi wo omotte iru", lyrics.romanization().get(0).text());
        for (app.morphe.extension.music.patches.lyrics.model.Word w : lyrics.lines().get(0).words()) {
            assertNull(w.romaji());
        }
    }
}
