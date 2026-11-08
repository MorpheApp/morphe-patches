/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4566
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

import org.junit.Assert;
import org.junit.Test;

import app.morphe.extension.music.patches.lyrics.model.LyricsRetiming;

public class SpringEasingTest {

    @Test
    public void testSpringEasing350() {
        SpringEasing.Result r = SpringEasing.get(350, 0.78, 350 * 1.5);
        Assert.assertEquals(880, r.durationMs);
        Assert.assertEquals(106, r.samples.length);

        float[][] expected = {
                {0.00f, 0.0000f},
                {0.05f, 0.0443f},
                {0.10f, 0.2754f},
                {0.20f, 0.8258f},
                {0.30f, 0.9828f},
                {0.40f, 1.0198f},
                {0.50f, 1.0182f},
                {0.70f, 1.0095f},
                {1.00f, 1.0000f}
        };

        for (float[] exp : expected) {
            float u = exp[0];
            float expectedVal = exp[1];
            float actualVal = r.interpolate(u);
            Assert.assertEquals("Mismatch at u=" + u, expectedVal, actualVal, 0.0005f);
        }
    }

    @Test
    public void testSpringEasing450() {
        SpringEasing.Result r = SpringEasing.get(450, 0.78, 450 * 1.5);
        Assert.assertEquals(1130, r.durationMs);
        Assert.assertEquals(136, r.samples.length);

        float[][] expected = {
                {0.00f, 0.0000f},
                {0.05f, 0.0441f},
                {0.10f, 0.2750f},
                {0.20f, 0.8259f},
                {0.30f, 0.9829f},
                {0.40f, 1.0198f},
                {0.50f, 1.0183f},
                {0.70f, 1.0097f},
                {1.00f, 1.0000f}
        };

        for (float[] exp : expected) {
            float u = exp[0];
            float expectedVal = exp[1];
            float actualVal = r.interpolate(u);
            Assert.assertEquals("Mismatch at u=" + u, expectedVal, actualVal, 0.0005f);
        }
    }

    @Test
    public void testDynamicZetaAndRelaxScrolling() {
        // Default zeta without gap
        Assert.assertEquals(0.78f, SpringEasing.computeZeta(0L, false), 0.0001f);
        Assert.assertEquals(0.80f, SpringEasing.computeZeta(0L, true), 0.0001f);

        // Tight gap -> closer to 0.85, Wide gap (2500ms+) -> 0.73 (or 0.78 in relax mode)
        Assert.assertEquals(0.73f, SpringEasing.computeZeta(2500L, false), 0.0001f);
        Assert.assertEquals(0.78f, SpringEasing.computeZeta(2500L, true), 0.0001f);

        // Relax scrolling duration (1.5x) and stagger ratio (0.05f vs 0.10f)
        long normalDur = LyricsRetiming.calculateScrollDuration(400L, false);
        long relaxDur = LyricsRetiming.calculateScrollDuration(400L, true);
        Assert.assertEquals(400L, normalDur);
        Assert.assertEquals(600L, relaxDur);

        Assert.assertEquals(40f, LyricsRetiming.calculateScrollDelayIncrementFloat(400L, false), 0.001f);
        Assert.assertEquals(30f, LyricsRetiming.calculateScrollDelayIncrementFloat(600L, true), 0.001f);

        // Upward scroll in relax mode halves delayIncrementMs
        SpringEasing.LyricsScrollStagger relaxUp = new SpringEasing.LyricsScrollStagger(
                false, 5, 600L, 30f, true, new int[0]);
        Assert.assertEquals(15f, relaxUp.delayIncrementMs, 0.001f);
    }

    @Test
    public void testCacheCapacity() {
        for (int i = 0; i < 100; i++) {
            SpringEasing.get(300 + i * 10, 0.78, 400);
        }
        // make sure cache working and memory safe
        SpringEasing.Result r = SpringEasing.get(350, 0.78, 525);
        Assert.assertNotNull(r);
    }

    @Test
    public void testStaggerAfterIndexForDownwardScroll() {
        // linesBefore[k] = count of non-gap lines in 0..(k-1). Suppose row 5 is a gap row:
        // indices:    0  1  2  3  4  5(gap) 6  7
        // linesBefore:0, 1, 2, 3, 4, 5,     5, 6, 7
        int[] linesBefore = new int[]{0, 1, 2, 3, 4, 5, 5, 6, 7};
        SpringEasing.LyricsScrollStagger downStagger = new SpringEasing.LyricsScrollStagger(
                true, 2, 3, 400L, 40f, false, linesBefore);
        Assert.assertEquals(0, downStagger.steps(2));
        Assert.assertEquals(0, downStagger.steps(3));
        Assert.assertEquals(0L, downStagger.delayFor(2));
        Assert.assertEquals(0L, downStagger.delayFor(3));
        Assert.assertEquals(1, downStagger.steps(4));
        Assert.assertEquals(40L, downStagger.delayFor(4));
        Assert.assertEquals(2, downStagger.steps(6));
        Assert.assertEquals(80L, downStagger.delayFor(6));

        // Multi-active group (rows 3..4 active when leaving row 2): rows 2, 3, 4 all have 0ms delay
        SpringEasing.LyricsScrollStagger multiGroupStagger = new SpringEasing.LyricsScrollStagger(
                true, 2, 4, 400L, 40f, false, new int[0]);
        Assert.assertEquals(0L, multiGroupStagger.delayFor(2));
        Assert.assertEquals(0L, multiGroupStagger.delayFor(3));
        Assert.assertEquals(0L, multiGroupStagger.delayFor(4));
        Assert.assertEquals(40L, multiGroupStagger.delayFor(5));
    }
}
