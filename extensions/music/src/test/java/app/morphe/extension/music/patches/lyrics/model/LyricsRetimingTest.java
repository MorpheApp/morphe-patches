/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4566
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.model;

import org.junit.Assert;
import org.junit.Test;

import java.util.List;

public class LyricsRetimingTest {

    @Test
    public void testRetimeLyricsExpandsEndToCoverWords() {
        Word w1 = new Word(1000, 1500, "Up", null, true);
        Word w2 = new Word(1500, 2450, "Down", null, false);
        LyricsLine line = new LyricsLine(1000, 2000, "Up Down", List.of(w1, w2));

        List<LyricsRetiming.RetimedEntry> retimed = LyricsRetiming.retimeLyrics(List.of(line), null, null);
        Assert.assertEquals(1, retimed.size());

        LyricsRetiming.RetimedEntry entry = retimed.get(0);
        Assert.assertEquals(1000, entry.startMs);
        Assert.assertTrue("actualEndTimeMs must cover last word endMs", entry.actualEndTimeMs >= 2450);
        Assert.assertTrue("adjustedEndTimeMs must cover last word endMs", entry.adjustedEndTimeMs >= 2450);
    }

    @Test
    public void testActiveLineImmediateSwitchWithoutLingering() {
        Word w1 = new Word(20000, 22000, "Up", null, true);
        Word w2 = new Word(22000, 22700, "심해", null, false);
        LyricsLine line1 = new LyricsLine(20000, 22700, "Up 심해", List.of(w1, w2));
        LyricsLine line2 = new LyricsLine(22700, 25000, "다음 가사", List.of());

        List<LyricsRetiming.RetimedEntry> retimed = LyricsRetiming.retimeLyrics(List.of(line1, line2), null, null);
        long[] maxEnd = LyricsRetiming.buildAdjustedEndMaxByIndex(retimed);

        long lookahead = 190L;

        // At 22500ms (lookahead pos 22690ms), line 0 is still active before handoff
        List<Integer> activeAt22500 = LyricsRetiming.findActiveRetimedLineIndices(
                retimed, maxEnd, 22500L + lookahead, 0L);
        Assert.assertTrue("Line 0 must be active before handoff", activeAt22500.contains(0));
        Assert.assertFalse("Line 1 has not activated yet", activeAt22500.contains(1));

        // At 22520ms (lookahead pos 22710ms), line 0 has immediately dropped and line 1 is active
        List<Integer> activeAt22520 = LyricsRetiming.findActiveRetimedLineIndices(
                retimed, maxEnd, 22520L + lookahead, 0L);
        Assert.assertFalse("Line 0 must immediately drop without lingering", activeAt22520.contains(0));
        Assert.assertTrue("Line 1 must be active", activeAt22520.contains(1));
    }

    @Test
    public void testActiveLineAtCurrentPosition() {
        Word w1 = new Word(20000, 22000, "Up", null, true);
        Word w2 = new Word(22000, 22700, "심해", null, false);
        LyricsLine line1 = new LyricsLine(20000, 22700, "Up 심해", List.of(w1, w2));
        LyricsLine line2 = new LyricsLine(22700, 25000, "다음 가사", List.of());

        List<LyricsRetiming.RetimedEntry> retimed = LyricsRetiming.retimeLyrics(List.of(line1, line2), null, null);
        long[] maxEnd = LyricsRetiming.buildAdjustedEndMaxByIndex(retimed);

        // at 22600ms, line 0 is active and line 1 has not started
        List<Integer> activeAt22600 = LyricsRetiming.findActiveRetimedLineIndices(retimed, maxEnd, 22600, 0L);
        Assert.assertTrue(activeAt22600.contains(0));
        Assert.assertFalse(activeAt22600.contains(1));

        // at 22701ms, line 0 has expired and line 1 is active
        List<Integer> activeAt22701 = LyricsRetiming.findActiveRetimedLineIndices(retimed, maxEnd, 22701, 0L);
        Assert.assertFalse(activeAt22701.contains(0));
        Assert.assertTrue(activeAt22701.contains(1));
    }

    @Test
    public void testMultipleActiveLinesOverlapping() {
        // Line 1 plays 10000ms to 16000ms, Line 2 plays 12000ms to 18000ms (overlapping duet/backing)
        LyricsLine line1 = new LyricsLine(10000, 16000, "First singer", List.of());
        LyricsLine line2 = new LyricsLine(12000, 18000, "Second singer", List.of());

        List<LyricsRetiming.RetimedEntry> retimed = LyricsRetiming.retimeLyrics(List.of(line1, line2), null, null);
        long[] maxEnd = LyricsRetiming.buildAdjustedEndMaxByIndex(retimed);

        // At 14000ms, both lines are active
        List<Integer> activeAt14000 = LyricsRetiming.findActiveRetimedLineIndices(retimed, maxEnd, 14000, 0L);
        Assert.assertTrue("Line 0 must be active at 14000ms", activeAt14000.contains(0));
        Assert.assertTrue("Line 1 must be active at 14000ms", activeAt14000.contains(1));

        // Both lines share cluster end in retimed cluster, remaining active through 17000ms
        List<Integer> activeAt17000 = LyricsRetiming.findActiveRetimedLineIndices(retimed, maxEnd, 17000, 0L);
        Assert.assertTrue("Line 0 must stay active until cluster end", activeAt17000.contains(0));
        Assert.assertTrue("Line 1 must stay active until cluster end", activeAt17000.contains(1));

        // Scroll anchor stays locked to line 0 during overlapping cluster
        int primary = LyricsRetiming.calculatePrimaryRetimedLineIndex(retimed, List.of(0, 1), 14000L, 0);
        Assert.assertEquals("Scroll anchor must stay locked to line 0 in duet cluster", 0, primary);

        // After cluster end (18601ms), both have expired
        List<Integer> activeAt18601 = LyricsRetiming.findActiveRetimedLineIndices(retimed, maxEnd, 18601, 0L);
        Assert.assertFalse("Line 0 expired after cluster end", activeAt18601.contains(0));
        Assert.assertFalse("Line 1 expired after cluster end", activeAt18601.contains(1));
    }

    @Test
    public void testSequentialLinesWithTinyBoundaryOverlapAdvanceProperly() {
        LyricsLine l0 = new LyricsLine(1000, 3100, "Line 0", List.of());
        LyricsLine l1 = new LyricsLine(3000, 5150, "Line 1", List.of());
        LyricsLine l2 = new LyricsLine(5000, 7100, "Line 2", List.of());
        LyricsLine l3 = new LyricsLine(7000, 9000, "Line 3", List.of());

        List<LyricsRetiming.RetimedEntry> retimed = LyricsRetiming.retimeLyrics(List.of(l0, l1, l2, l3), null, null);
        long[] maxEnd = LyricsRetiming.buildAdjustedEndMaxByIndex(retimed);

        int lastPrimary = -1;
        long[] times = new long[]{1500L, 3500L, 5500L, 7500L};
        for (int i = 0; i < times.length; i++) {
            List<Integer> active = LyricsRetiming.findActiveRetimedLineIndices(retimed, maxEnd, times[i], 0L);
            int primary = LyricsRetiming.calculatePrimaryRetimedLineIndex(retimed, active, times[i], lastPrimary);
            Assert.assertEquals("Primary line must advance at " + times[i] + "ms", i, primary);
            lastPrimary = primary;
        }
    }

    @Test
    public void testEstimatedEndOfLineWithoutEnd() {
        LyricsLine line = new LyricsLine(1000, "Short", List.of());
        // A close next line ends this one.
        Assert.assertEquals(9000, LyricsRetiming.estimateEndMs(line, 9000));
        // After a long pause the line is held a few seconds, and ends before the pause.
        Assert.assertEquals(6000, LyricsRetiming.estimateEndMs(line, 30000));
        Assert.assertEquals(5500, LyricsRetiming.estimateEndMs(line, 12500));
        // The last line is held a few seconds.
        Assert.assertEquals(6000, LyricsRetiming.estimateEndMs(line, LyricsLine.NO_TIME));
    }
}
