/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4439
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** Direct tests for {@link PairedPunctuation#restore} and {@link PairedPunctuation#crossedAt}, which re-wrap chunks split across punctuation shells and identify which shell a split crosses. */
public class PairedPunctuationExtrasTest {

    // ── restore ──

    @Test
    public void restoreWrapsChunkInParentheses() {
        assertEquals("(hello)",
                PairedPunctuation.restore("(hello world)", "hello", 1, 6));
    }

    @Test
    public void restoreWrapsChunkInNestedBrackets() {
        assertEquals("[(hello)]",
                PairedPunctuation.restore("[(hello world)]", "hello", 2, 7));
    }

    @Test
    public void restoreNoEnclosingShellUnchanged() {
        assertEquals("hello",
                PairedPunctuation.restore("hello world", "hello", 0, 5));
    }

    @Test
    public void restorePartialShellUnchanged() {
        assertEquals("hello",
                PairedPunctuation.restore("(hello world", "hello", 1, 6));
    }

    @Test
    public void restoreQuotesWrapChunk() {
        assertEquals("\"hello\"",
                PairedPunctuation.restore("\"hello world\"", "hello", 1, 6));
    }

    @Test
    public void restoreChunkExtendingPastShellGetsNoClosing() {
        // ")x" is not a shell, so the chunk passes through unchanged.
        assertEquals("hello world)x",
                PairedPunctuation.restore("(hello world)x", "hello world)x", 1, 14));
    }

    @Test
    public void restoreFullwidthBracketsWrapChunk() {
        assertEquals("（hello）",
                PairedPunctuation.restore("（hello world）", "hello", 1, 6));
    }

    // ── crossedAt ──

    @Test
    public void crossedAtFindsPairWhenMidInsideParentheses() {
        PairedPunctuation.Pair pair = PairedPunctuation.crossedAt("say (hello world) now", 10);
        assertNotNull(pair);
        assertEquals("(", pair.opening());
        assertEquals(")", pair.closing());
    }

    @Test
    public void crossedAtReturnsNullWhenMidOutsideAllPairs() {
        assertNull(PairedPunctuation.crossedAt("hello world", 5));
    }

    @Test
    public void crossedAtReturnsNullForWholeLineShell() {
        // A whole-line shell is completed by restore, so crossedAt excludes it.
        assertNull(PairedPunctuation.crossedAt("(hello world)", 6));
    }

    @Test
    public void crossedAtMidRightAfterOpeningFindsPair() {
        PairedPunctuation.Pair pair = PairedPunctuation.crossedAt("say (hello world) now", 5);
        assertNotNull(pair);
        assertEquals("(", pair.opening());
    }

    @Test
    public void crossedAtMidAtOpeningReturnsNull() {
        assertNull(PairedPunctuation.crossedAt("say (hello world) now", 4));
    }

    @Test
    public void crossedAtMidAfterClosingReturnsNull() {
        assertNull(PairedPunctuation.crossedAt("say (hello world) now", 17));
    }

    @Test
    public void crossedAtMidAtClosingFindsPair() {
        PairedPunctuation.Pair pair = PairedPunctuation.crossedAt("say (hello world) now", 16);
        assertNotNull(pair);
        assertEquals("(", pair.opening());
    }
}
