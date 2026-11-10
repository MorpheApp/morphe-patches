/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4439
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.parsers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

import app.morphe.extension.music.patches.lyrics.model.LyricsLine;
import app.morphe.extension.music.patches.lyrics.model.Word;

public class KRCParserTest {

    @Test
    public void parseNullOrEmptyReturnsEmpty() {
        assertTrue(KRCParser.parse(null).isEmpty());
        assertTrue(KRCParser.parse("").isEmpty());
    }

    @Test
    public void parseBasicLineTiming() {
        List<LyricsLine> lines = KRCParser.parse("[1000,500]Hello world");
        assertEquals(1, lines.size());
        LyricsLine line = lines.get(0);
        assertEquals(1000L, line.startTimeMs());
        assertEquals(1500L, line.endTimeMs());
        assertEquals("Hello world", line.text());
    }

    @Test
    public void parseWithWordTiming() {
        List<LyricsLine> lines = KRCParser.parse("[1000,500]<0,100,0>Hello<100,400,0> world");
        assertEquals(1, lines.size());
        LyricsLine line = lines.get(0);
        assertEquals(1000L, line.startTimeMs());
        assertEquals(1500L, line.endTimeMs());
        assertEquals("Hello world", line.text());
        assertTrue(line.hasWordTimings());
        List<Word> words = line.words();
        assertEquals(2, words.size());
        assertEquals("Hello", words.get(0).text());
        assertEquals(1000L, words.get(0).startMs());
        assertEquals(1100L, words.get(0).endMs());
        assertEquals(" world", words.get(1).text());
        assertEquals(1100L, words.get(1).startMs());
        assertEquals(1500L, words.get(1).endMs());
    }

    @Test
    public void parseMultipleLines() {
        List<LyricsLine> lines = KRCParser.parse(
                "[1000,500]First\n[2000,300]Second\n[3000,200]Third");
        assertEquals(3, lines.size());
        assertEquals("First", lines.get(0).text());
        assertEquals("Second", lines.get(1).text());
        assertEquals("Third", lines.get(2).text());
    }

    @Test
    public void parseLinesAreSortedByStartTime() {
        List<LyricsLine> lines = KRCParser.parse(
                "[3000,200]Third\n[1000,500]First\n[2000,300]Second");
        assertEquals(3, lines.size());
        assertEquals(1000L, lines.get(0).startTimeMs());
        assertEquals(2000L, lines.get(1).startTimeMs());
        assertEquals(3000L, lines.get(2).startTimeMs());
    }

    @Test
    public void parseLineWithoutLeadingBracketIsSkipped() {
        List<LyricsLine> lines = KRCParser.parse(
                "Not a KRC line\n[1000,500]Hello");
        assertEquals(1, lines.size());
        assertEquals("Hello", lines.get(0).text());
    }

    @Test
    public void parseEmptyContentLineIsSkipped() {
        List<LyricsLine> lines = KRCParser.parse(
                "[1000,500]\n[2000,300]Hello");
        assertEquals(1, lines.size());
        assertEquals("Hello", lines.get(0).text());
    }

    @Test
    public void parseWordTimingLastWordEndsAtLineEnd() {
        List<LyricsLine> lines = KRCParser.parse("[1000,1000]<0,400,0>First<400,600,0>Second");
        assertEquals(1, lines.size());
        LyricsLine line = lines.get(0);
        assertEquals(2000L, line.endTimeMs());
        List<Word> words = line.words();
        assertEquals(2, words.size());
        assertEquals(1400L, words.get(0).endMs());
        assertEquals(2000L, words.get(1).endMs());
    }

    @Test
    public void parseContentWithoutWordTagsIsFullText() {
        List<LyricsLine> lines = KRCParser.parse("[1000,500]Hello world");
        assertEquals(1, lines.size());
        LyricsLine line = lines.get(0);
        assertEquals("Hello world", line.text());
        assertFalse(line.hasWordTimings());
    }

    @Test
    public void parseMixedWordAndPlainContent() {
        List<LyricsLine> lines = KRCParser.parse("[1000,500]<0,200,0>Hello plain text");
        assertEquals(1, lines.size());
        LyricsLine line = lines.get(0);
        assertEquals("Hello plain text", line.text());
        assertEquals(1, line.words().size());
    }

    @Test
    public void parseWhitespaceVariants() {
        List<LyricsLine> lines = KRCParser.parse(
                "  [1000,500]Hello  \n  [2000,300]World  ");
        assertEquals(2, lines.size());
        assertEquals("Hello", lines.get(0).text());
        assertEquals("World", lines.get(1).text());
    }
}
