/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4709
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
        assertEquals(300L, callCorrectSmoothEstimate(0L, 300L));
    }

    @Test
    public void correctSmoothEstimateMediumErrorUsesFortyFive() throws Exception {
        assertEquals(90L, callCorrectSmoothEstimate(0L, 200L));
    }

    @Test
    public void correctSmoothEstimateSmallErrorUsesTwenty() throws Exception {
        assertEquals(20L, callCorrectSmoothEstimate(0L, 100L));
    }

    @Test
    public void correctSmoothEstimateNegativeError() throws Exception {
        assertEquals(90L, callCorrectSmoothEstimate(100L, 50L));
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
    public void correctSmoothEstimateBoundaryError120() throws Exception {
        assertEquals(24L, callCorrectSmoothEstimate(0L, 120L));
    }

    @Test
    public void correctSmoothEstimateBoundaryError121() throws Exception {
        assertEquals(54L, callCorrectSmoothEstimate(0L, 121L));
    }

    @Test
    public void correctSmoothEstimateBoundaryError250() throws Exception {
        assertEquals(113L, callCorrectSmoothEstimate(0L, 250L));
    }

    @Test
    public void correctSmoothEstimateBoundaryError251() throws Exception {
        assertEquals(251L, callCorrectSmoothEstimate(0L, 251L));
    }

    @Test
    public void correctSmoothEstimateLargeNegative() throws Exception {
        assertEquals(0L, callCorrectSmoothEstimate(0L, -200L));
    }

    @Test
    public void correctSmoothEstimateFromNonZeroBase() throws Exception {
        assertEquals(520L, callCorrectSmoothEstimate(500L, 600L));
    }

    private static long callCorrectSmoothEstimate(long current, long reported) throws Exception {
        Method method = LyricsManager.class.getDeclaredMethod("correctSmoothEstimate", long.class, long.class);
        method.setAccessible(true);
        return (long) method.invoke(null, current, reported);
    }
}
