/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4592
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.music.patches.lyrics.LyricsTestEnv;
import app.morphe.extension.music.patches.lyrics.model.LyricsLine;
import app.morphe.extension.music.patches.lyrics.model.Word;

public class SectionDotsMotionTest {

    @Before
    public void setUp() {
        LyricsTestEnv.bootstrap();
    }

    @Test
    public void nullLinesReturnsEmpty() {
        assertTrue(SectionDotsMotion.computeGaps(null).isEmpty());
    }

    @Test
    public void emptyLinesReturnsEmpty() {
        assertTrue(SectionDotsMotion.computeGaps(List.of()).isEmpty());
    }

    @Test
    public void singleLineNoGapReturnsEmpty() {
        LyricsLine line = new LyricsLine(0, "hello");
        assertTrue(SectionDotsMotion.computeGaps(List.of(line)).isEmpty());
    }

    @Test
    public void firstLineStartsAfterMinGap() {
        LyricsLine line = new LyricsLine(5000, "hello");
        List<SectionDotsMotion.Gap> gaps = SectionDotsMotion.computeGaps(List.of(line));
        assertEquals(1, gaps.size());
        assertEquals(0, gaps.get(0).tagLineIndex());
        assertEquals(0L, gaps.get(0).gapStartMs());
        assertEquals(5000L, gaps.get(0).gapEndMs());
    }

    @Test
    public void gapBetweenTwoLines() {
        LyricsLine first = new LyricsLine(0, 2000, "hello", List.of());
        LyricsLine second = new LyricsLine(6000, "world");
        List<SectionDotsMotion.Gap> gaps = SectionDotsMotion.computeGaps(List.of(first, second));
        assertEquals(1, gaps.size());
        assertEquals(1, gaps.get(0).tagLineIndex());
        assertEquals(2000L, gaps.get(0).gapStartMs());
        assertEquals(6000L, gaps.get(0).gapEndMs());
    }

    @Test
    public void noGapBetweenTwoLines() {
        LyricsLine first = new LyricsLine(0, 2000, "hello", List.of());
        LyricsLine second = new LyricsLine(3000, "world");
        assertTrue(SectionDotsMotion.computeGaps(List.of(first, second)).isEmpty());
    }

    @Test
    public void bgLineSkippedForGap() {
        LyricsLine first = new LyricsLine(0, 2000, "hello", List.of());
        LyricsLine bg = new LyricsLine(2000, "bg vocal", List.of(), null, false, true);
        LyricsLine third = new LyricsLine(6000, "world");
        // The BG line is skipped, so the gap is measured from line 0's end to line 2's start.
        List<SectionDotsMotion.Gap> gaps = SectionDotsMotion.computeGaps(List.of(first, bg, third));
        assertEquals(1, gaps.size());
        assertEquals(2, gaps.get(0).tagLineIndex());
        assertEquals(2000L, gaps.get(0).gapStartMs());
        assertEquals(6000L, gaps.get(0).gapEndMs());
    }

    @Test
    public void nullLineInListSkipped() {
        LyricsLine first = new LyricsLine(0, 2000, "hello", List.of());
        LyricsLine third = new LyricsLine(6000, "world");
        List<LyricsLine> lines = new ArrayList<>();
        lines.add(first);
        lines.add(null);
        lines.add(third);
        List<SectionDotsMotion.Gap> gaps = SectionDotsMotion.computeGaps(lines);
        assertEquals(1, gaps.size());
        assertEquals(2, gaps.get(0).tagLineIndex());
    }

    @Test
    public void lineUsesWordEndWhenNoEndTime() {
        LyricsLine first = new LyricsLine(0, "hello",
                List.of(new Word(0, 2500, "hello")));
        LyricsLine second = new LyricsLine(6000, "world");
        List<SectionDotsMotion.Gap> gaps = SectionDotsMotion.computeGaps(List.of(first, second));
        assertEquals(1, gaps.size());
        assertEquals(2500L, gaps.get(0).gapStartMs());
    }

    @Test
    public void lineUsesInferredEndForShortText() {
        LyricsLine first = new LyricsLine(0, "hi");
        LyricsLine second = new LyricsLine(6000, "world");
        List<SectionDotsMotion.Gap> gaps = SectionDotsMotion.computeGaps(List.of(first, second));
        assertEquals(1, gaps.size());
        assertEquals(1380L, gaps.get(0).gapStartMs());
    }

    @Test
    public void lineInferredEndUsesMaxChars() {
        String longText = "a".repeat(100);
        LyricsLine first = new LyricsLine(0, longText);
        LyricsLine second = new LyricsLine(9000, "world");
        List<SectionDotsMotion.Gap> gaps = SectionDotsMotion.computeGaps(List.of(first, second));
        assertEquals(1, gaps.size());
        assertEquals(4800L, gaps.get(0).gapStartMs());
    }

    @Test
    public void multipleGapsDetected() {
        LyricsLine first = new LyricsLine(0, 2000, "a", List.of());
        LyricsLine second = new LyricsLine(6000, 8000, "b", List.of());
        LyricsLine third = new LyricsLine(12000, 14000, "c", List.of());
        List<SectionDotsMotion.Gap> gaps = SectionDotsMotion.computeGaps(
                List.of(first, second, third));
        assertEquals(2, gaps.size());
        assertEquals(1, gaps.get(0).tagLineIndex());
        assertEquals(2000L, gaps.get(0).gapStartMs());
        assertEquals(6000L, gaps.get(0).gapEndMs());
        assertEquals(2, gaps.get(1).tagLineIndex());
        assertEquals(8000L, gaps.get(1).gapStartMs());
        assertEquals(12000L, gaps.get(1).gapEndMs());
    }

    @Test
    public void leadingNullLinesSkippedBeforeFirst() {
        List<LyricsLine> lines = new ArrayList<>();
        lines.add(null);
        lines.add(null);
        lines.add(new LyricsLine(5000, "hello"));
        List<SectionDotsMotion.Gap> gaps = SectionDotsMotion.computeGaps(lines);
        assertEquals(1, gaps.size());
        assertEquals(2, gaps.get(0).tagLineIndex());
    }
}
