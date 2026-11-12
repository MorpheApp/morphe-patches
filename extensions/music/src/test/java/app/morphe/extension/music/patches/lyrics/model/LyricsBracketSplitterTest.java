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

import app.morphe.extension.music.patches.lyrics.LyricsFixtures;

public class LyricsBracketSplitterTest {

    @Test
    public void noBracketsReturnsUnchanged() {
        Lyrics lyrics = LyricsFixtures.synced(new LyricsLine(0, "hello world"));
        Lyrics result = LyricsBracketSplitter.apply(lyrics);
        assertEquals(1, result.lines().size());
        assertEquals("hello world", result.lines().get(0).text());
    }

    @Test
    public void emptyLyricsReturnsUnchanged() {
        assertEquals(0, LyricsBracketSplitter.apply(Lyrics.NOT_FOUND).lines().size());
        assertEquals(0, LyricsBracketSplitter.apply(LyricsFixtures.synced()).lines().size());
    }

    @Test
    public void nullReturnsNull() {
        assertNull(LyricsBracketSplitter.apply(null));
    }

    @Test
    public void backgroundAtStartSplitsIntoSungThenBackground() {
        Lyrics lyrics = LyricsFixtures.synced(new LyricsLine(0, "(oh) hello"));
        Lyrics result = LyricsBracketSplitter.apply(lyrics);
        assertEquals(2, result.lines().size());
        assertEquals("hello", result.lines().get(0).text());
        assertFalse(result.lines().get(0).isBG());
        assertEquals("oh", result.lines().get(1).text());
        assertTrue(result.lines().get(1).isBG());
    }

    @Test
    public void backgroundAtEndSplitsIntoSungThenBackground() {
        Lyrics lyrics = LyricsFixtures.synced(new LyricsLine(0, "hello (background)"));
        Lyrics result = LyricsBracketSplitter.apply(lyrics);
        assertEquals(2, result.lines().size());
        assertEquals("hello", result.lines().get(0).text());
        assertFalse(result.lines().get(0).isBG());
        assertEquals("background", result.lines().get(1).text());
        assertTrue(result.lines().get(1).isBG());
    }

    @Test
    public void backgroundAtBothEndsMergesBracketsIntoOneBackgroundLine() {
        Lyrics lyrics = LyricsFixtures.synced(new LyricsLine(0, "(oh) hello (background)"));
        Lyrics result = LyricsBracketSplitter.apply(lyrics);
        assertEquals(2, result.lines().size());
        assertEquals("hello", result.lines().get(0).text());
        assertFalse(result.lines().get(0).isBG());
        assertEquals("oh background", result.lines().get(1).text());
        assertTrue(result.lines().get(1).isBG());
    }

    @Test
    public void fullwidthBracketsWorkWithoutSpaces() {
        Lyrics lyrics = LyricsFixtures.synced(new LyricsLine(0, "（oh）hello"));
        Lyrics result = LyricsBracketSplitter.apply(lyrics);
        assertEquals(2, result.lines().size());
        assertEquals("hello", result.lines().get(0).text());
        assertFalse(result.lines().get(0).isBG());
        assertEquals("oh", result.lines().get(1).text());
        assertTrue(result.lines().get(1).isBG());
    }

    @Test
    public void alreadyBackgroundLineNotSplit() {
        Lyrics lyrics = new Lyrics(List.of(new LyricsLine(0, "bg", List.of(),
                null, false, true)), "p", true,
                null, null, null, null, null, null, null);
        Lyrics result = LyricsBracketSplitter.apply(lyrics);
        assertEquals(1, result.lines().size());
        assertEquals("bg", result.lines().get(0).text());
    }

    @Test
    public void bracketsWithoutSpaceAreNotSplit() {
        // "(oh)hello" has no space after the bracket and Latin follows, so it is not a background run.
        Lyrics lyrics = LyricsFixtures.synced(new LyricsLine(0, "(oh)hello"));
        Lyrics result = LyricsBracketSplitter.apply(lyrics);
        assertEquals(1, result.lines().size());
        assertEquals("(oh)hello", result.lines().get(0).text());
    }

    @Test
    public void bracketAtStartWithoutSpaceSplitsWhenCjkFollows() {
        // No space, but CJK follows, so it is a background run.
        Lyrics lyrics = LyricsFixtures.synced(new LyricsLine(0, "(oh)你好"));
        Lyrics result = LyricsBracketSplitter.apply(lyrics);
        assertEquals(2, result.lines().size());
        assertEquals("你好", result.lines().get(0).text());
        assertFalse(result.lines().get(0).isBG());
        assertEquals("oh", result.lines().get(1).text());
        assertTrue(result.lines().get(1).isBG());
    }

    @Test
    public void bracketAtEndWithoutSpaceSplitsWhenCjkPrecedes() {
        // No space, but CJK precedes, so it is a background run.
        Lyrics lyrics = LyricsFixtures.synced(new LyricsLine(0, "你好(oh)"));
        Lyrics result = LyricsBracketSplitter.apply(lyrics);
        assertEquals(2, result.lines().size());
        assertEquals("你好", result.lines().get(0).text());
        assertFalse(result.lines().get(0).isBG());
        assertEquals("oh", result.lines().get(1).text());
        assertTrue(result.lines().get(1).isBG());
    }

    @Test
    public void bracketsAtBothEndsWithoutSpaceSplitWhenCjkBetween() {
        // No spaces, but CJK between, so both are background runs.
        Lyrics lyrics = LyricsFixtures.synced(new LyricsLine(0, "(oh)你好(ah)"));
        Lyrics result = LyricsBracketSplitter.apply(lyrics);
        assertEquals(2, result.lines().size());
        assertEquals("你好", result.lines().get(0).text());
        assertFalse(result.lines().get(0).isBG());
        assertEquals("oh ah", result.lines().get(1).text());
        assertTrue(result.lines().get(1).isBG());
    }

    @Test
    public void cjkBracketContentWithoutSpaceSplits() {
        // Bracket content is CJK, no space after, but CJK follows.
        Lyrics lyrics = LyricsFixtures.synced(new LyricsLine(0, "(哦)你好"));
        Lyrics result = LyricsBracketSplitter.apply(lyrics);
        assertEquals(2, result.lines().size());
        assertEquals("你好", result.lines().get(0).text());
        assertFalse(result.lines().get(0).isBG());
        assertEquals("哦", result.lines().get(1).text());
        assertTrue(result.lines().get(1).isBG());
    }

    @Test
    public void hangulBracketWithoutSpaceSplits() {
        // Hangul follows the bracket, so it is a background run.
        Lyrics lyrics = LyricsFixtures.synced(new LyricsLine(0, "(오)안녕"));
        Lyrics result = LyricsBracketSplitter.apply(lyrics);
        assertEquals(2, result.lines().size());
        assertEquals("안녕", result.lines().get(0).text());
        assertFalse(result.lines().get(0).isBG());
        assertEquals("오", result.lines().get(1).text());
        assertTrue(result.lines().get(1).isBG());
    }

    @Test
    public void nonRoundBracketsNotSplit() {
        // Square brackets quote rather than mark background vocals.
        Lyrics lyrics = LyricsFixtures.synced(new LyricsLine(0, "[oh] hello"));
        Lyrics result = LyricsBracketSplitter.apply(lyrics);
        assertEquals(1, result.lines().size());
        assertEquals("[oh] hello", result.lines().get(0).text());
    }

    @Test
    public void preservesProviderAndSynced() {
        Lyrics lyrics = LyricsFixtures.synced(new LyricsLine(0, "(oh) hello"));
        Lyrics result = LyricsBracketSplitter.apply(lyrics);
        assertEquals("TestProvider", result.providerName());
        assertTrue(result.synced());
    }
}
