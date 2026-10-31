/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4208
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

public class BoxBlurTest {

    private static final int WIDTH = 24;
    private static final int HEIGHT = 48;

    private static int[] blur(int[] src) {
        int[] dst = new int[src.length];
        BoxBlur.blur(src, new int[src.length], dst, WIDTH, HEIGHT, 2, 4);
        return dst;
    }

    @Test
    public void testAlternatingColorsBlend() {
        int[] src = new int[WIDTH * HEIGHT];
        for (int i = 0; i < src.length; i++) {
            src[i] = (i % 2 == 0) ? 0xFFFF0000 : 0xFF0000FF;
        }

        int mid = blur(src)[(HEIGHT / 2) * WIDTH + (WIDTH / 2)];
        int r = (mid >> 16) & 0xFF;
        int b = mid & 0xFF;
        assertTrue("Red channel should be blended around 127, got " + r, r > 100 && r < 155);
        assertTrue("Blue channel should be blended around 127, got " + b, b > 100 && b < 155);
    }

    @Test
    public void testSolidColorUnchanged() {
        int[] src = new int[WIDTH * HEIGHT];
        Arrays.fill(src, 0xFF336699);
        assertArrayEquals(src, blur(src));
    }
}
