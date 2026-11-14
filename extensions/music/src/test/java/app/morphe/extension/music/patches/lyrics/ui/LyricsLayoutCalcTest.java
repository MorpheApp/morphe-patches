/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.util.List;

import app.morphe.extension.music.patches.lyrics.LyricsTestEnv;
import app.morphe.extension.music.patches.lyrics.model.LyricsLine;
import app.morphe.extension.music.patches.lyrics.ui.GapLineView.GapInterval;

public class LyricsLayoutCalcTest {

    private static final String LTR = "hello world";
    private static final String RTL = "مرحبا بالعالم";

    @Before
    public void setUp() {
        LyricsTestEnv.bootstrap();
    }

    private static LyricsLine line(long startMs, long endMs, String text,
                                   boolean isDuet, boolean isBG) {
        return new LyricsLine(startMs, endMs, text, List.of(), null, isDuet, isBG, null);
    }

    private static boolean[] noSec(int n) {
        return new boolean[n];
    }

    // -- rightAlignedLines ----------------------------------------------------

    @Test
    public void ltrNonDuetAllLeftAligned() {
        List<LyricsLine> lines = List.of(
                line(0, 5000, LTR, false, false),
                line(6000, 5000, LTR, false, false));
        boolean[] right = LyricsLayoutCalc.rightAlignedLines(lines, noSec(2));
        assertFalse(right[0]);
        assertFalse(right[1]);
    }

    @Test
    public void rtlNonDuetAllRightAligned() {
        List<LyricsLine> lines = List.of(
                line(0, 5000, RTL, false, false),
                line(6000, 5000, RTL, false, false));
        boolean[] right = LyricsLayoutCalc.rightAlignedLines(lines, noSec(2));
        assertTrue(right[0]);
        assertTrue(right[1]);
    }

    @Test
    public void duetSongFlipsDuetLineToRight() {
        List<LyricsLine> lines = List.of(
                line(0, 5000, LTR, false, false),
                line(6000, 5000, LTR, true, false));
        boolean[] right = LyricsLayoutCalc.rightAlignedLines(lines, noSec(2));
        assertFalse(right[0]);
        assertTrue(right[1]);
    }

    @Test
    public void backgroundVocalFollowsLeadDuetSide() {
        // Duet song: the lead is a duet, so its background vocal sits on the duet side.
        List<LyricsLine> lines = List.of(
                line(0, 5000, LTR, true, false),
                line(200, 5000, LTR, false, true));
        boolean[] right = LyricsLayoutCalc.rightAlignedLines(lines, new boolean[]{false, true});
        assertTrue(right[0]);
        assertTrue(right[1]);
    }

    @Test
    public void backgroundVocalOnNonDuetLeadStaysLeft() {
        List<LyricsLine> lines = List.of(
                line(0, 5000, LTR, false, false),
                line(200, 5000, LTR, false, true));
        boolean[] right = LyricsLayoutCalc.rightAlignedLines(lines, new boolean[]{false, true});
        assertFalse(right[0]);
        assertFalse(right[1]);
    }

    // -- buildGapIntervals ---------------------------------------------------

    @Test
    public void noGapsWhenLinesAreClose() {
        List<LyricsLine> lines = List.of(
                line(0, 5000, LTR, false, false),
                line(1000, 5000, LTR, false, false));
        boolean[] right = noSec(2);
        assertTrue(LyricsLayoutCalc.buildGapIntervals(lines, noSec(2), right).isEmpty());
    }

    @Test
    public void gapBeforeFirstLineWhenLongPause() {
        List<LyricsLine> lines = List.of(line(8000, 5000, LTR, false, false));
        List<GapInterval> gaps = LyricsLayoutCalc.buildGapIntervals(lines, noSec(1), noSec(1));
        assertEquals(1, gaps.size());
        GapInterval gap = gaps.get(0);
        assertEquals(0L, gap.startMs());
        assertEquals(0, gap.beforeLineIndex());
        assertTrue(gap.endMs() >= gap.startMs() + LyricsLayoutCalc.MIN_GAP_DOTS_MS);
    }

    @Test
    public void noGapBeforeFirstLineWhenPauseShort() {
        List<LyricsLine> lines = List.of(line(1000, 5000, LTR, false, false));
        assertTrue(LyricsLayoutCalc.buildGapIntervals(lines, noSec(1), noSec(1)).isEmpty());
    }

    @Test
    public void gapBetweenLinesWhenLongPause() {
        List<LyricsLine> lines = List.of(
                line(0, 5000, LTR, false, false),
                line(13000, 5000, LTR, false, false));
        List<GapInterval> gaps = LyricsLayoutCalc.buildGapIntervals(lines, noSec(2), noSec(2));
        assertEquals(1, gaps.size());
        GapInterval gap = gaps.get(0);
        assertEquals(1, gap.beforeLineIndex());
        // The dots start after the previous line's end plus the start lag.
        assertEquals(5000L + GapLineView.LYRICS_GAP_START_LAG_MS, gap.startMs());
        assertTrue(gap.endMs() >= gap.startMs() + LyricsLayoutCalc.MIN_GAP_DOTS_MS);
    }

    @Test
    public void backgroundVocalsSkippedAsLead() {
        // The middle line is a background vocal, so it is not a lead and yields no gap of its own.
        List<LyricsLine> lines = List.of(
                line(0, 5000, LTR, false, false),
                line(6000, 5000, LTR, false, true),
                line(13000, 5000, LTR, false, false));
        List<GapInterval> gaps = LyricsLayoutCalc.buildGapIntervals(
                lines, new boolean[]{false, true, false}, noSec(3));
        assertEquals(1, gaps.size());
        assertEquals(2, gaps.get(0).beforeLineIndex());
    }

    @Test
    public void noTimeLinesSkipped() {
        List<LyricsLine> lines = List.of(
                line(LyricsLine.NO_TIME, 5000, LTR, false, false),
                line(13000, 5000, LTR, false, false));
        assertTrue(LyricsLayoutCalc.buildGapIntervals(lines, noSec(2), noSec(2)).isEmpty());
    }

    @Test
    public void gapEndsAtNextLineStartMinusExit() {
        List<LyricsLine> lines = List.of(
                line(0, 5000, LTR, false, false),
                line(13000, 5000, LTR, false, false));
        List<GapInterval> gaps = LyricsLayoutCalc.buildGapIntervals(lines, noSec(2), noSec(2));
        GapInterval gap = gaps.get(0);
        assertEquals(13000L, gap.nextStartMs());
    }
}
