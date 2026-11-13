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
import java.util.Set;

import app.morphe.extension.music.patches.lyrics.LyricsTestEnv;
import app.morphe.extension.music.patches.lyrics.model.LyricsLine;
import app.morphe.extension.music.patches.lyrics.model.LyricsRetiming;
import app.morphe.extension.music.patches.lyrics.ui.LyricsActiveLines.Context;
import app.morphe.extension.music.patches.lyrics.ui.LyricsActiveLines.LineSecondary;
import app.morphe.extension.music.patches.lyrics.ui.LyricsActiveLines.SecondaryMap;

public class LyricsActiveLinesTest {

    @Before
    public void setUp() {
        LyricsTestEnv.bootstrap();
    }

    private static LyricsLine line(long startMs, long endMs, String text, boolean isBG) {
        return new LyricsLine(startMs, endMs, text, List.of(), null, false, isBG, null);
    }

    private static LyricsRetiming.RetimedEntry entry(
            long startMs, long actualEndMs, long adjustedEndMs, boolean isSecondary) {
        return new LyricsRetiming.RetimedEntry(
                line(startMs, actualEndMs, "text", isSecondary),
                startMs, actualEndMs, adjustedEndMs, isSecondary);
    }

    private static LineSecondary ls(boolean isLineView, boolean isSecondary, int parent) {
        return new LineSecondary(isLineView, isSecondary, parent);
    }

    private static Context ctx(List<LyricsRetiming.RetimedEntry> retimed,
                               LineSecondary[] secondary, List<Boolean> lineIsBackground) {
        return new Context(retimed,
                LyricsRetiming.buildAdjustedEndMaxByIndex(retimed),
                lineIsBackground, new SecondaryMap(secondary));
    }

    private static Context ctx(List<LyricsRetiming.RetimedEntry> retimed,
                               LineSecondary[] secondary) {
        return ctx(retimed, secondary, List.of());
    }

    @Test
    public void isSecondaryLineAtNonPositiveIsFalse() {
        Context c = ctx(List.of(entry(0, 5000, 5000, false)), new LineSecondary[]{ls(true, false, -1)});
        assertFalse(LyricsActiveLines.isSecondaryLineAt(0, c));
        assertFalse(LyricsActiveLines.isSecondaryLineAt(-1, c));
    }

    @Test
    public void isSecondaryLineAtLineViewSecondaryIsTrue() {
        Context c = ctx(List.of(entry(0, 5000, 5000, false)),
                new LineSecondary[]{ls(true, false, -1), ls(true, true, 0)});
        assertTrue(LyricsActiveLines.isSecondaryLineAt(1, c));
    }

    @Test
    public void isSecondaryLineAtLineViewSecondaryNoParentIsFalse() {
        // The view answers, so retimedLyrics is not consulted.
        Context c = ctx(List.of(entry(0, 5000, 5000, false), entry(100, 5000, 5000, true)),
                new LineSecondary[]{ls(true, false, -1), ls(true, true, -1)});
        assertFalse(LyricsActiveLines.isSecondaryLineAt(1, c));
    }

    @Test
    public void isSecondaryLineAtLineViewNotSecondaryReturnsFalse() {
        // The view answers, so retimedLyrics is not consulted.
        Context c = ctx(List.of(entry(0, 5000, 5000, false), entry(100, 5000, 5000, true)),
                new LineSecondary[]{ls(true, false, -1), ls(true, false, -1)});
        assertFalse(LyricsActiveLines.isSecondaryLineAt(1, c));
    }

    @Test
    public void isSecondaryLineAtNonLineViewFallsThroughToRetimed() {
        Context c = ctx(List.of(entry(0, 5000, 5000, false), entry(100, 5000, 5000, true)),
                new LineSecondary[]{ls(false, false, -1), ls(false, false, -1)});
        assertTrue(LyricsActiveLines.isSecondaryLineAt(1, c));
    }

    @Test
    public void isSecondaryLineAtBeyondViewsUsesRetimed() {
        Context c = ctx(List.of(entry(0, 5000, 5000, false), entry(6000, 5000, 5000, true)),
                new LineSecondary[]{ls(true, false, -1)});
        assertTrue(LyricsActiveLines.isSecondaryLineAt(1, c));
    }

    @Test
    public void isSecondaryLineAtBeyondViewsAndRetimedUsesLineIsBackground() {
        Context c = ctx(List.of(entry(0, 5000, 5000, false)),
                new LineSecondary[]{ls(true, false, -1)}, List.of(false, true));
        assertTrue(LyricsActiveLines.isSecondaryLineAt(1, c));
    }

    @Test
    public void isSecondaryLineAtBeyondAllIsFalse() {
        Context c = ctx(List.of(entry(0, 5000, 5000, false)),
                new LineSecondary[]{ls(true, false, -1)}, List.of(false));
        assertFalse(LyricsActiveLines.isSecondaryLineAt(2, c));
    }

    @Test
    public void leadLineIndexNonSecondaryReturnsIndex() {
        Context c = ctx(List.of(entry(0, 5000, 5000, false)),
                new LineSecondary[]{ls(true, false, -1)});
        assertEquals(0, LyricsActiveLines.leadLineIndex(0, c));
    }

    @Test
    public void leadLineIndexSecondaryResolvesToParent() {
        Context c = ctx(List.of(entry(0, 5000, 5000, false), entry(100, 4000, 4000, true)),
                new LineSecondary[]{ls(true, false, -1), ls(true, true, 0)});
        assertEquals(0, LyricsActiveLines.leadLineIndex(1, c));
    }

    @Test
    public void leadLineIndexSecondaryChainResolvesToLead() {
        Context c = ctx(List.of(entry(0, 5000, 5000, false),
                        entry(100, 4000, 4000, true), entry(200, 3000, 3000, true)),
                new LineSecondary[]{ls(true, false, -1), ls(true, true, 0), ls(true, true, 1)});
        assertEquals(0, LyricsActiveLines.leadLineIndex(2, c));
    }

    @Test
    public void leadLineIndexOutOfBoundsReturnsIndex() {
        Context c = ctx(List.of(entry(0, 5000, 5000, false)),
                new LineSecondary[]{ls(true, false, -1)});
        assertEquals(3, LyricsActiveLines.leadLineIndex(3, c));
    }

    @Test
    public void leadLineIndicesDedupesAndSorts() {
        Context c = ctx(List.of(entry(0, 5000, 5000, false), entry(100, 4000, 4000, true),
                        entry(6000, 5000, 5000, false)),
                new LineSecondary[]{ls(true, false, -1), ls(true, true, 0), ls(true, false, -1)});
        List<Integer> leads = LyricsActiveLines.leadLineIndices(List.of(1, 1, 2), c);
        assertEquals(List.of(0, 2), leads);
    }

    @Test
    public void computeActiveLinesEmptyRetimedIsEmpty() {
        Context c = ctx(List.of(), new LineSecondary[0]);
        assertTrue(LyricsActiveLines.computeActiveLines(0, c).isEmpty());
    }

    @Test
    public void computeActiveLinesReturnsSungLine() {
        Context c = ctx(List.of(entry(0, 5000, 5000, false), entry(6000, 10000, 10000, false)),
                new LineSecondary[]{ls(true, false, -1), ls(true, false, -1)});
        Set<Integer> active = LyricsActiveLines.computeActiveLines(6100, c);
        assertTrue(active.contains(1));
    }

    @Test
    public void computeActiveLinesActivatesBackgroundVocalOfActiveLead() {
        Context c = ctx(List.of(entry(0, 5000, 5000, false), entry(100, 4000, 4000, true)),
                new LineSecondary[]{ls(true, false, -1), ls(true, true, 0)});
        Set<Integer> active = LyricsActiveLines.computeActiveLines(100, c);
        assertTrue(active.contains(0));
        assertTrue(active.contains(1));
    }

    @Test
    public void computeActiveLinesHoldsClosedLateBackgroundVocal() {
        // Vocal starts late and scanTime is far from its start, so it stays closed.
        Context c = ctx(List.of(entry(0, 10000, 10000, false), entry(2000, 8000, 8000, true)),
                new LineSecondary[]{ls(true, false, -1), ls(true, true, 0)});
        Set<Integer> active = LyricsActiveLines.computeActiveLines(0, c);
        assertTrue(active.contains(0));
        assertFalse(active.contains(1));
    }

    @Test
    public void computeActiveLinesOpensLateBackgroundVocalNearItsStart() {
        Context c = ctx(List.of(entry(0, 10000, 10000, false), entry(2000, 8000, 8000, true)),
                new LineSecondary[]{ls(true, false, -1), ls(true, true, 0)});
        Set<Integer> active = LyricsActiveLines.computeActiveLines(1800, c);
        assertTrue(active.contains(0));
        assertTrue(active.contains(1));
    }

    @Test
    public void computeActiveLinesClosesEarlyEndedBackgroundVocal() {
        Context c = ctx(List.of(entry(0, 10000, 10000, false), entry(100, 4000, 4000, true)),
                new LineSecondary[]{ls(true, false, -1), ls(true, true, 0)});
        Set<Integer> active = LyricsActiveLines.computeActiveLines(5000, c);
        assertTrue(active.contains(0));
        assertFalse(active.contains(1));
    }

    @Test
    public void computePrimaryActiveIndexEmptyRetimedIsMinusOne() {
        Context c = ctx(List.of(), new LineSecondary[0]);
        assertEquals(-1, LyricsActiveLines.computePrimaryActiveIndex(
                Set.of(0), 0, c, -1));
    }

    @Test
    public void computePrimaryActiveIndexReturnsLeadOfActive() {
        Context c = ctx(List.of(entry(0, 5000, 5000, false), entry(6000, 10000, 10000, false)),
                new LineSecondary[]{ls(true, false, -1), ls(true, false, -1)});
        assertEquals(1, LyricsActiveLines.computePrimaryActiveIndex(Set.of(1), 6100, c, -1));
    }

    @Test
    public void computePrimaryActiveIndexResolvesSecondaryToLead() {
        Context c = ctx(List.of(entry(0, 5000, 5000, false), entry(100, 4000, 4000, true)),
                new LineSecondary[]{ls(true, false, -1), ls(true, true, 0)});
        assertEquals(0, LyricsActiveLines.computePrimaryActiveIndex(Set.of(1), 100, c, -1));
    }

    @Test
    public void lineEndMsUsesLineEndTime() {
        Context c = ctx(List.of(entry(0, 5000, 5000, false)),
                new LineSecondary[]{ls(true, false, -1)});
        assertEquals(5000L, LyricsActiveLines.lineEndMs(
                List.of(line(0, 5000, "a", false)), 0, LyricsLine.NO_TIME, c));
    }

    @Test
    public void lineEndMsUsesLastWordEndWhenLineHasNoEnd() {
        Context c = ctx(List.of(entry(0, 5000, 5000, false)),
                new LineSecondary[]{ls(true, false, -1)});
        assertEquals(3000L, LyricsActiveLines.lineEndMs(
                List.of(line(0, LyricsLine.NO_TIME, "a", false)), 0, 3000L, c));
    }

    @Test
    public void lineEndMsUsesRetimedEndWhenNoEndAndNoWord() {
        Context c = ctx(List.of(entry(0, 7000, 7000, false)),
                new LineSecondary[]{ls(true, false, -1)});
        assertEquals(7000L, LyricsActiveLines.lineEndMs(
                List.of(line(0, LyricsLine.NO_TIME, "a", false)), 0, LyricsLine.NO_TIME, c));
    }

    @Test
    public void lineEndMsFallsBackToNextLineStart() {
        Context c = ctx(List.of(entry(0, LyricsLine.NO_TIME, LyricsLine.NO_TIME, false)),
                new LineSecondary[]{ls(true, false, -1)});
        assertEquals(6000L, LyricsActiveLines.lineEndMs(
                List.of(line(0, LyricsLine.NO_TIME, "a", false), line(6000, 9000, "b", false)),
                0, LyricsLine.NO_TIME, c));
    }

    @Test
    public void lineEndMsLastLineWithoutTimeIsMaxValue() {
        Context c = ctx(List.of(entry(0, LyricsLine.NO_TIME, LyricsLine.NO_TIME, false)),
                new LineSecondary[]{ls(true, false, -1)});
        assertEquals(Long.MAX_VALUE, LyricsActiveLines.lineEndMs(
                List.of(line(0, LyricsLine.NO_TIME, "a", false)), 0, LyricsLine.NO_TIME, c));
    }

    @Test
    public void currentPauseAfterLineMsEmptyRetimedIsMinValue() {
        Context c = ctx(List.of(), new LineSecondary[0]);
        assertEquals(Long.MIN_VALUE, LyricsActiveLines.currentPauseAfterLineMs(0, 0, c));
    }

    @Test
    public void currentPauseAfterLineMsComputesGapToNext() {
        Context c = ctx(List.of(entry(0, 5000, 5000, false), entry(6000, 10000, 10000, false)),
                new LineSecondary[]{ls(true, false, -1), ls(true, false, -1)});
        assertEquals(1000L, LyricsActiveLines.currentPauseAfterLineMs(5500, 0, c));
    }

    @Test
    public void currentPauseAfterLineMsExtendsRawEndByBackgroundVocal() {
        Context c = ctx(List.of(
                        entry(0, 5000, 5000, false),
                        entry(100, 7000, 7000, true),
                        entry(12000, 15000, 15000, false)),
                new LineSecondary[]{ls(true, false, -1), ls(true, true, 0), ls(true, false, -1)});
        // The vocal ends at 7000, so the pause to the next lead is 12000 - 7000.
        assertEquals(5000L, LyricsActiveLines.currentPauseAfterLineMs(5500, 0, c));
    }

    @Test
    public void currentPauseAfterLineMsIsMinValueAfterLastLine() {
        Context c = ctx(List.of(entry(0, 5000, 5000, false)),
                new LineSecondary[]{ls(true, false, -1)});
        assertEquals(Long.MIN_VALUE, LyricsActiveLines.currentPauseAfterLineMs(6000, 0, c));
    }
}
