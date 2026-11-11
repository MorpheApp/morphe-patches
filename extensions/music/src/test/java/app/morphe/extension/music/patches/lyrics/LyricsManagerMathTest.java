/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics;

import static org.junit.Assert.assertEquals;

import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Method;

public class LyricsManagerMathTest {

    @Before
    public void setUp() {
        LyricsTestEnv.bootstrap();
    }

    @Test
    public void correctSmoothEstimateLargeErrorUsesFullFactor() throws Exception {
        assertEquals(200L, callCorrectSmoothEstimate(0L, 200L));
    }

    @Test
    public void correctSmoothEstimateMediumErrorUsesEightyFive() throws Exception {
        assertEquals(85L, callCorrectSmoothEstimate(0L, 100L));
    }

    @Test
    public void correctSmoothEstimateSmallErrorUsesHalf() throws Exception {
        assertEquals(10L, callCorrectSmoothEstimate(0L, 20L));
    }

    @Test
    public void correctSmoothEstimateNegativeError() throws Exception {
        assertEquals(58L, callCorrectSmoothEstimate(100L, 50L));
    }

    @Test
    public void correctSmoothEstimateClampsToZero() throws Exception {
        assertEquals(0L, callCorrectSmoothEstimate(0L, -100L));
    }

    @Test
    public void correctSmoothEstimateNoError() throws Exception {
        assertEquals(100L, callCorrectSmoothEstimate(100L, 100L));
    }

    @Test
    public void correctSmoothEstimateBoundaryError150() throws Exception {
        assertEquals(128L, callCorrectSmoothEstimate(0L, 150L));
    }

    @Test
    public void correctSmoothEstimateBoundaryError151() throws Exception {
        assertEquals(151L, callCorrectSmoothEstimate(0L, 151L));
    }

    @Test
    public void correctSmoothEstimateBoundaryError30() throws Exception {
        assertEquals(15L, callCorrectSmoothEstimate(0L, 30L));
    }

    @Test
    public void correctSmoothEstimateBoundaryError31() throws Exception {
        assertEquals(26L, callCorrectSmoothEstimate(0L, 31L));
    }

    @Test
    public void correctSmoothEstimateLargeNegative() throws Exception {
        assertEquals(0L, callCorrectSmoothEstimate(0L, -200L));
    }

    @Test
    public void correctSmoothEstimateFromNonZeroBase() throws Exception {
        assertEquals(585L, callCorrectSmoothEstimate(500L, 600L));
    }

    private static long callCorrectSmoothEstimate(long current, long reported) throws Exception {
        Method method = LyricsManager.class.getDeclaredMethod("correctSmoothEstimate", long.class, long.class);
        method.setAccessible(true);
        return (long) method.invoke(null, current, reported);
    }
}
