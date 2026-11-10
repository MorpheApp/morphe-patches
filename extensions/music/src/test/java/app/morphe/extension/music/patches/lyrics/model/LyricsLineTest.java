/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4439
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class LyricsLineTest {

    @Test
    public void noTimeConstantIsMinusOne() {
        assertEquals(-1L, LyricsLine.NO_TIME);
    }

    @Test
    public void hasWordsFalseWhenNoWords() {
        LyricsLine line = new LyricsLine(0, "text");
        assertFalse(line.hasWords());
    }

    @Test
    public void hasWordsTrueWhenWordsPresent() {
        LyricsLine line = new LyricsLine(0, 100, "text",
                List.of(new Word(0, 50, "text")), null, false, false, null);
        assertTrue(line.hasWords());
    }

    @Test
    public void hasWordTimingsFalseForSingleWordMatchingLine() {
        Word word = new Word(0, 1000, "hello world");
        LyricsLine line = new LyricsLine(0, 1000, "hello world", List.of(word),
                null, false, false, null);
        assertFalse(line.hasWordTimings());
    }

    @Test
    public void hasWordTimingsTrueForMultipleWords() {
        LyricsLine line = new LyricsLine(0, 1000, "hello world",
                List.of(new Word(0, 500, "hello"), new Word(500, 1000, "world")),
                null, false, false, null);
        assertTrue(line.hasWordTimings());
    }

    @Test
    public void hasWordTimingsFalseForZeroDurationSingleWord() {
        Word word = new Word(0, 0, "hello");
        LyricsLine line = new LyricsLine(0, 100, "hello", List.of(word),
                null, false, false, null);
        assertFalse(line.hasWordTimings());
    }

    @Test
    public void isCenteredFalseForNullAgent() {
        assertFalse(new LyricsLine(0, "x").isCentered());
    }

    @Test
    public void isCenteredTrueForV1000Agent() {
        LyricsLine line = new LyricsLine(0, "x", List.of(), "v1000", false, false);
        assertTrue(line.isCentered());
    }

    @Test
    public void isCenteredTrueForV2000Agent() {
        LyricsLine line = new LyricsLine(0, "x", List.of(), "V2000", false, false);
        assertTrue(line.isCentered());
    }

    @Test
    public void isCenteredFalseForOtherAgent() {
        LyricsLine line = new LyricsLine(0, "x", List.of(), "v999", false, false);
        assertFalse(line.isCentered());
    }

    @Test
    public void constructorNullWordsBecomesEmptyList() {
        LyricsLine line = new LyricsLine(0, "text", null);
        assertTrue(line.words().isEmpty());
    }

    @Test
    public void constructorNullTextBecomesEmpty() {
        LyricsLine line = new LyricsLine(0, null);
        assertEquals("", line.text());
    }

    @Test
    public void wordConstructorVariants() {
        Word w1 = new Word(0, 100, "hi");
        assertNull(w1.romaji());
        assertFalse(w1.endsWithSpace());
        assertNull(w1.ruby());

        Word w2 = new Word(0, 100, "hi", "h i", true);
        assertEquals("h i", w2.romaji());
        assertTrue(w2.endsWithSpace());

        Word w3 = new Word(0, 100, "hi", "h i", true, "ruby");
        assertEquals("ruby", w3.ruby());
    }
}
