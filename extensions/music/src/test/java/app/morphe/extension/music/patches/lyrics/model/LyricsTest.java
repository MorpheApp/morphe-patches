/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4439
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.music.patches.lyrics.LyricsFixtures;

public class LyricsTest {

    @Test
    public void isEmptyTrueForNoLines() {
        assertTrue(Lyrics.NOT_FOUND.isEmpty());
    }

    @Test
    public void isEmptyFalseWhenLinesPresent() {
        assertFalse(new Lyrics(List.of(new LyricsLine(0, "a")), "p", false,
                null, null, null, null, null, null, null).isEmpty());
    }

    @Test
    public void indexForPositionReturnsFirstLineWhenPastStart() {
        Lyrics lyrics = LyricsFixtures.synced(
                new LyricsLine(0, 1000, "a", List.of(), null, false, false, null),
                new LyricsLine(1000, 2000, "b", List.of(), null, false, false, null));
        assertEquals(0, lyrics.indexForPosition(500, -1));
        assertEquals(1, lyrics.indexForPosition(1500, -1));
    }

    @Test
    public void indexForPositionReturnsMinusOneBeforeFirstLine() {
        Lyrics lyrics = LyricsFixtures.synced(new LyricsLine(1000, "a"));
        assertEquals(-1, lyrics.indexForPosition(0, -1));
        assertEquals(-1, lyrics.indexForPosition(999, -1));
    }

    @Test
    public void indexForPositionUnsyncedReturnsMinusOne() {
        Lyrics lyrics = LyricsFixtures.plain("a", "b");
        assertEquals(-1, lyrics.indexForPosition(0, -1));
    }

    @Test
    public void indexForPositionSkipsBackgroundLines() {
        Lyrics lyrics = new Lyrics(List.of(
                new LyricsLine(0, 1000, "a", List.of(), null, false, false, null),
                new LyricsLine(1000, 2000, "bg", List.of(), null, false, true, null)),
                "p", true, null, null, null, null, null, null, null);
        // The highlighted line is background, so it falls back to the previous.
        assertEquals(0, lyrics.indexForPosition(1500, -1));
    }

    @Test
    public void indexForPositionUsesHintToAvoidScan() {
        Lyrics lyrics = LyricsFixtures.synced(
                new LyricsLine(0, 1000, "a", List.of(), null, false, false, null),
                new LyricsLine(1000, 2000, "b", List.of(), null, false, false, null));
        assertEquals(0, lyrics.indexForPosition(500, 0));
    }

    @Test
    public void withLinesReplacesLinesKeepsOtherFields() {
        Lyrics original = LyricsFixtures.synced(new LyricsLine(0, "old"));
        Lyrics updated = original.withLines(List.of(new LyricsLine(0, "new")));
        assertEquals(1, updated.lines().size());
        assertEquals("new", updated.lines().get(0).text());
        assertEquals(original.providerName(), updated.providerName());
        assertEquals(original.synced(), updated.synced());
    }

    @Test
    public void sortScoredByScoreOrdersDescending() {
        List<Lyrics.ScoredLyrics> scored = new ArrayList<>();
        scored.add(new Lyrics.ScoredLyrics(10, LyricsFixtures.synced(new LyricsLine(0, "low"))));
        scored.add(new Lyrics.ScoredLyrics(90, LyricsFixtures.synced(new LyricsLine(0, "high"))));
        scored.add(new Lyrics.ScoredLyrics(50, LyricsFixtures.synced(new LyricsLine(0, "mid"))));
        Lyrics.sortScoredByScore(scored);
        assertEquals(90, scored.get(0).score());
        assertEquals(50, scored.get(1).score());
        assertEquals(10, scored.get(2).score());
    }

    @Test
    public void fixAnomalousWordTimestampsLeavesValidUntouched() {
        Word word = new Word(0, 100, "hello");
        LyricsLine line = new LyricsLine(0, 200, "hello", List.of(word),
                null, false, false, null);
        List<LyricsLine> result = Lyrics.fixAnomalousWordTimestamps(List.of(line));
        assertEquals(1, result.size());
        assertEquals("hello", result.get(0).words().get(0).text());
        assertEquals(0, result.get(0).words().get(0).startMs());
        assertEquals(100, result.get(0).words().get(0).endMs());
    }

    @Test
    public void clampLastWordEndsClampsToNextLineStart() {
        Word word = new Word(0, 2000, "hello");
        LyricsLine line0 = new LyricsLine(0, 2000, "hello", List.of(word),
                null, false, false, null);
        LyricsLine line1 = new LyricsLine(2000, 3000, "next", List.of(),
                null, false, false, null);
        List<LyricsLine> result = Lyrics.clampLastWordEnds(List.of(line0, line1));
        assertEquals(2000, result.get(0).words().get(0).endMs());
    }
}
