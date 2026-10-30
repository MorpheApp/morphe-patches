/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4090
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.model;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class LyricsLineWordTimingsTest {

    @Test public void singleWordWithRealDurationHasWordTimings() {
        final LyricsLine line = new LyricsLine(1000, 2000, "Yeah",
                List.of(new Word(1000, 2000, "Yeah")));
        assertTrue(line.hasWordTimings());
    }

    @Test public void singleWordWithoutDurationDoesNot() {
        final LyricsLine point = new LyricsLine(1000, 1000, "Yeah",
                List.of(new Word(1000, 1000, "Yeah")));
        assertFalse(point.hasWordTimings());

        final LyricsLine reversed = new LyricsLine(1000, 500, "Yeah",
                List.of(new Word(1000, 500, "Yeah")));
        assertFalse(reversed.hasWordTimings());
    }

    @Test public void singleSpanCoveringAMultiWordLineDoesNot() {
        final LyricsLine line = new LyricsLine(0, 1000, "hello world",
                List.of(new Word(0, 1000, "hello world")));
        assertFalse(line.hasWordTimings());
    }

    /** A line made of one word repeated is still one word, so its span may be real timing. */
    @Test public void singleSpanOverARepeatedWordIsNotMistakenForALineSpan() {
        final LyricsLine line = new LyricsLine(0, 1000, "la la la",
                List.of(new Word(0, 1000, "la la la")));
        assertFalse(line.hasWordTimings());

        final LyricsLine word = new LyricsLine(0, 1000, "Yeah Yeah",
                List.of(new Word(0, 1000, "Yeah")));
        assertFalse(word.hasWordTimings());
    }

    /**
     * Documents the known edge case: a script without spaces counts as one word, so a single
     * full-line placeholder span is treated as word timing.
     */
    @Test public void cjkLineWithOnePlaceholderSpanIsTreatedAsWordTimed() {
        final LyricsLine line = new LyricsLine(0, 1000, "你好世界",
                List.of(new Word(0, 1000, "你好世界")));
        assertTrue(line.hasWordTimings());
    }
}
