/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class LyricsLineMathTest {

    private static final float DELTA = 1e-6f;

    @Test
    public void secondaryScaleClosedIsTheClosedSize() {
        assertEquals(0.85f, LyricsLineMath.secondaryScale(0f), DELTA);
    }

    @Test
    public void secondaryScaleOpenIsFullSize() {
        assertEquals(1f, LyricsLineMath.secondaryScale(1f), DELTA);
    }

    @Test
    public void secondaryScaleHalfwayIsBetweenTheTwo() {
        assertEquals(0.925f, LyricsLineMath.secondaryScale(0.5f), DELTA);
    }

    private static final float[] LEAD = {0f, 10f, 20f};
    private static final float[] TRAIL = {9f, 19f, 29f};

    @Test
    public void findSyllableByXReturnsTheContainingRange() {
        assertEquals(0, LyricsLineMath.findSyllableByX(5f, LEAD, TRAIL));
        assertEquals(1, LyricsLineMath.findSyllableByX(15f, LEAD, TRAIL));
        assertEquals(2, LyricsLineMath.findSyllableByX(25f, LEAD, TRAIL));
    }

    @Test
    public void findSyllableByXIncludesTheRangeEdges() {
        assertEquals(0, LyricsLineMath.findSyllableByX(0f, LEAD, TRAIL));
        assertEquals(0, LyricsLineMath.findSyllableByX(9f, LEAD, TRAIL));
        assertEquals(1, LyricsLineMath.findSyllableByX(10f, LEAD, TRAIL));
    }

    @Test
    public void findSyllableByXBetweenSyllablesReturnsTheNearest() {
        assertEquals(0, LyricsLineMath.findSyllableByX(9.5f, LEAD, TRAIL));
        assertEquals(1, LyricsLineMath.findSyllableByX(12f, LEAD, TRAIL));
        assertEquals(2, LyricsLineMath.findSyllableByX(29.5f, LEAD, TRAIL));
    }

    @Test
    public void findSyllableByXOutsideEverythingReturnsTheNearest() {
        assertEquals(0, LyricsLineMath.findSyllableByX(-20f, LEAD, TRAIL));
        assertEquals(2, LyricsLineMath.findSyllableByX(100f, LEAD, TRAIL));
    }

    @Test
    public void findSyllableByXWithoutSyllablesReturnsMinusOne() {
        assertEquals(-1, LyricsLineMath.findSyllableByX(5f, new float[0], new float[0]));
    }

    @Test
    public void alpha255ScalesAndRounds() {
        assertEquals(0, LyricsLineMath.alpha255(0f));
        assertEquals(255, LyricsLineMath.alpha255(1f));
        assertEquals(128, LyricsLineMath.alpha255(0.5f));
        assertEquals(51, LyricsLineMath.alpha255(0.2f));
    }

    @Test
    public void alpha255ClampsOutsideTheUnitRange() {
        assertEquals(0, LyricsLineMath.alpha255(-0.5f));
        assertEquals(255, LyricsLineMath.alpha255(1.5f));
    }

    @Test
    public void extendSungStartsAtTheFirstEdge() {
        assertEquals(7f, LyricsLineMath.extendSung(Float.NaN, 7f, false), DELTA);
        assertEquals(7f, LyricsLineMath.extendSung(Float.NaN, 7f, true), DELTA);
    }

    @Test
    public void extendSungTakesTheFurthestPointLeftToRight() {
        assertEquals(9f, LyricsLineMath.extendSung(5f, 9f, false), DELTA);
        assertEquals(9f, LyricsLineMath.extendSung(9f, 5f, false), DELTA);
    }

    @Test
    public void extendSungTakesTheFurthestPointRightToLeft() {
        assertEquals(5f, LyricsLineMath.extendSung(5f, 9f, true), DELTA);
        assertEquals(5f, LyricsLineMath.extendSung(9f, 5f, true), DELTA);
    }
}
