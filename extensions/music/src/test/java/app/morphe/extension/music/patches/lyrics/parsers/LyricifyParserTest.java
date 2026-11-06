/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4439
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.parsers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

import app.morphe.extension.music.patches.lyrics.model.LyricsLine;
import app.morphe.extension.music.patches.lyrics.model.Word;

public class LyricifyParserTest {

    // ── parseLines ──

    @Test
    public void parseLinesNullOrEmptyReturnsEmpty() {
        assertTrue(LyricifyParser.parseLines(null, 0).isEmpty());
        assertTrue(LyricifyParser.parseLines("", 0).isEmpty());
    }

    @Test
    public void parseLinesBasicFormat() {
        List<LyricsLine> lines = LyricifyParser.parseLines("[1000,2000]Hello world", 0);
        assertEquals(1, lines.size());
        assertEquals(1000L, lines.get(0).startTimeMs());
        assertEquals(2000L, lines.get(0).endTimeMs());
        assertEquals("Hello world", lines.get(0).text());
    }

    @Test
    public void parseLinesWithOffset() {
        List<LyricsLine> lines = LyricifyParser.parseLines("[1000,2000]Hello", 500);
        assertEquals(1, lines.size());
        assertEquals(1500L, lines.get(0).startTimeMs());
        assertEquals(2500L, lines.get(0).endTimeMs());
    }

    @Test
    public void parseLinesMultiple() {
        List<LyricsLine> lines = LyricifyParser.parseLines(
                "[1000,2000]First\n[3000,4000]Second", 0);
        assertEquals(2, lines.size());
        assertEquals("First", lines.get(0).text());
        assertEquals("Second", lines.get(1).text());
    }

    @Test
    public void parseLinesSortedByStartTime() {
        List<LyricsLine> lines = LyricifyParser.parseLines(
                "[3000,4000]Third\n[1000,2000]First", 0);
        assertEquals(2, lines.size());
        assertEquals(1000L, lines.get(0).startTimeMs());
        assertEquals(3000L, lines.get(1).startTimeMs());
    }

    @Test
    public void parseLinesEmptyBodySkipped() {
        List<LyricsLine> lines = LyricifyParser.parseLines("[1000,2000]", 0);
        assertTrue(lines.isEmpty());
    }

    @Test
    public void parseLinesNoMatchSkipped() {
        List<LyricsLine> lines = LyricifyParser.parseLines("Not a lyricify line", 0);
        assertTrue(lines.isEmpty());
    }

    // ── parseSyllable ──

    @Test
    public void parseSyllableNullOrEmptyReturnsEmpty() {
        assertTrue(LyricifyParser.parseSyllable(null, 0).isEmpty());
        assertTrue(LyricifyParser.parseSyllable("", 0).isEmpty());
    }

    @Test
    public void parseSyllableBasicFormat() {
        List<LyricsLine> lines = LyricifyParser.parseSyllable("Hello(1000,500) world(1500,500)", 0);
        assertEquals(1, lines.size());
        LyricsLine line = lines.get(0);
        assertEquals(1000L, line.startTimeMs());
        assertEquals(2000L, line.endTimeMs());
        assertEquals("Hello world", line.text());
        assertTrue(line.hasWordTimings());
        List<Word> words = line.words();
        assertEquals(2, words.size());
        assertEquals("Hello", words.get(0).text());
        assertEquals(" world", words.get(1).text());
    }

    @Test
    public void parseSyllableWithOffset() {
        List<LyricsLine> lines = LyricifyParser.parseSyllable("Hello(1000,500)", 100);
        assertEquals(1, lines.size());
        assertEquals(1100L, lines.get(0).startTimeMs());
    }

    @Test
    public void parseSyllableWithBracketPrefix() {
        List<LyricsLine> lines = LyricifyParser.parseSyllable("[00:01]Hello(1000,500)", 0);
        assertEquals(1, lines.size());
        assertEquals("Hello", lines.get(0).text());
    }

    // ── parseTranslation ──

    @Test
    public void parseTranslationNullOrEmptyReturnsEmpty() {
        assertTrue(LyricifyParser.parseTranslation(null, 0).isEmpty());
        assertTrue(LyricifyParser.parseTranslation("", 0).isEmpty());
    }

    @Test
    public void parseTranslationBasicFormat() {
        List<String> result = LyricifyParser.parseTranslation("[01:00.00]Hola\n[02:00.00]Mundo", 0);
        assertEquals(2, result.size());
        assertEquals("Hola", result.get(0));
        assertEquals("Mundo", result.get(1));
    }

    @Test
    public void parseTranslationWithOffset() {
        List<String> result = LyricifyParser.parseTranslation("[01:00.00]Hola", 1000);
        assertEquals(1, result.size());
        assertEquals("Hola", result.get(0));
    }

    @Test
    public void parseTranslationSortedByTime() {
        List<String> result = LyricifyParser.parseTranslation("[02:00.00]Second\n[01:00.00]First", 0);
        assertEquals(2, result.size());
        assertEquals("First", result.get(0));
        assertEquals("Second", result.get(1));
    }

    @Test
    public void parseTranslationNoMatchSkipped() {
        List<String> result = LyricifyParser.parseTranslation("Not a translation", 0);
        assertTrue(result.isEmpty());
    }

    @Test
    public void parseTranslationEmptyTextSkipped() {
        List<String> result = LyricifyParser.parseTranslation("[01:00.00]", 0);
        assertTrue(result.isEmpty());
    }
}
