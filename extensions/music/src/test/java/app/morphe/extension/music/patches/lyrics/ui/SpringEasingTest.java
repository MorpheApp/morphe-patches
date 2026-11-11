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
    public void testSpringStartsAndSettles() {
        SpringEasing.LyricsSpring spring = new SpringEasing.LyricsSpring(350);
        Assert.assertEquals(875f, spring.totalMs, 0.001f);
        Assert.assertEquals(0f, spring.value(0f), 0f);
        Assert.assertEquals(1f, spring.value(spring.totalMs), 0f);
        // Overshoots at the peak, then settles back towards 1.
        Assert.assertTrue(spring.value(350f) > 1f);
        Assert.assertTrue(spring.value(700f) < spring.value(350f));
        Assert.assertTrue(spring.value(700f) > 1f);
    }

    @Test
    public void testDynamicZeta() {
        Assert.assertEquals(0.78f, SpringEasing.computeZeta(0L), 0.0001f);
        // Lines in quick succession get the tightest damping, long pauses the widest.
        Assert.assertEquals(0.85f, SpringEasing.computeZeta(1L), 0.001f);
        Assert.assertEquals(0.73f, SpringEasing.computeZeta(2500L), 0.0001f);
        Assert.assertEquals(0.73f, SpringEasing.computeZeta(10000L), 0.0001f);

        Assert.assertEquals(400L, LyricsRetiming.calculateScrollDuration(400L));
        Assert.assertEquals(450L, LyricsRetiming.calculateScrollDuration(900L));
        Assert.assertEquals(40f, LyricsRetiming.calculateScrollDelayIncrement(400L), 0.001f);
    }

    @Test
    public void testSpringCache() {
        SpringEasing.LyricsSpring first = SpringEasing.springFor(350, 0);
        for (int i = 0; i < 200; i++) {
            SpringEasing.springFor(300 + i * 10, i * 20L);
        }
        SpringEasing.LyricsSpring again = SpringEasing.springFor(350, 0);
        Assert.assertEquals(first.totalMs, again.totalMs, 0f);
        Assert.assertSame(again, SpringEasing.springFor(352, 0));
    }

    @Test
    public void testStaggerAfterIndexForDownwardScroll() {
        // linesBefore[k] = count of non-gap lines in 0..(k-1). Suppose row 5 is a gap row:
        // indices:    0  1  2  3  4  5(gap) 6  7
        // linesBefore:0, 1, 2, 3, 4, 5,     5, 6, 7
        int[] linesBefore = new int[]{0, 1, 2, 3, 4, 5, 5, 6, 7};
        SpringEasing.LyricsScrollStagger downStagger = new SpringEasing.LyricsScrollStagger(
                true, 2, 3, 400L, 40f, linesBefore);
        Assert.assertEquals(0, downStagger.steps(2));
        Assert.assertEquals(0, downStagger.steps(3));
        Assert.assertEquals(1, downStagger.steps(4));
        Assert.assertEquals(1, downStagger.steps(5));
        Assert.assertEquals(2, downStagger.steps(6));

        // Multi-active group (rows 3..4 active when leaving row 2): rows 2, 3, 4 move together.
        int[] noGaps = new int[]{0, 1, 2, 3, 4, 5, 6, 7, 8};
        SpringEasing.LyricsScrollStagger multiGroupStagger = new SpringEasing.LyricsScrollStagger(
                true, 2, 4, 400L, 40f, noGaps);
        Assert.assertEquals(0, multiGroupStagger.steps(2));
        Assert.assertEquals(0, multiGroupStagger.steps(3));
        Assert.assertEquals(0, multiGroupStagger.steps(4));
        Assert.assertEquals(1, multiGroupStagger.steps(5));
    }

    @Test
    public void testStaggerForUpwardScroll() {
        int[] noGaps = new int[]{0, 1, 2, 3, 4, 5, 6, 7, 8};
        SpringEasing.LyricsScrollStagger upStagger = new SpringEasing.LyricsScrollStagger(
                false, 5, 5, 400L, 40f, noGaps);
        Assert.assertEquals(0, upStagger.steps(5));
        Assert.assertEquals(0, upStagger.steps(7));
        Assert.assertEquals(1, upStagger.steps(4));
        Assert.assertEquals(3, upStagger.steps(2));
    }

    @Test
    public void testUsableHitWordAndSyllableTokens() {
        String text = "Everything every day";
        // Whole word "every" must match the standalone word at index 11, not inside "Everything"
        int hitWord = LyricsSpanBuilder.usableHit(text, text.length(), 0, "every", true);
        Assert.assertEquals(11, hitWord);

        // Syllable tokens of "Everything" must match sequentially even without word boundaries
        int hitSyl1 = LyricsSpanBuilder.usableHit(text, text.length(), 0, "Every", false);
        Assert.assertEquals(0, hitSyl1);
        int hitSyl2 = LyricsSpanBuilder.usableHit(text, text.length(), 5, "thing", true);
        Assert.assertEquals(5, hitSyl2);
    }

    @Test
    public void testSkipNewlines() {
        String singleLf = "hello\nworld";
        Assert.assertEquals(6, LyricsSpanBuilder.skipNewlines(singleLf, 5));
        Assert.assertEquals(0, LyricsSpanBuilder.skipNewlines(singleLf, 0));

        String multiLf = "line1\n\nline2";
        Assert.assertEquals(7, LyricsSpanBuilder.skipNewlines(multiLf, 5));

        String crLf = "line1\r\nline2";
        Assert.assertEquals(7, LyricsSpanBuilder.skipNewlines(crLf, 5));

        String trailingLf = "line1\n";
        Assert.assertEquals(6, LyricsSpanBuilder.skipNewlines(trailingLf, 5));
    }
}
