/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DynamicBackgroundViewTest {

    @Test
    public void testSeparableBlur24x48() {
        int w = DynamicBackgroundView.BUFFER_WIDTH;
        int h = DynamicBackgroundView.BUFFER_HEIGHT;
        assertEquals(24, w);
        assertEquals(48, h);
        assertEquals(50L, DynamicBackgroundView.FRAME_INTERVAL_MS);

        int[] src = new int[w * h];
        int[] temp = new int[w * h];
        int[] dst = new int[w * h];

        for (int i = 0; i < src.length; i++) {
            src[i] = (i % 2 == 0) ? 0xFFFF0000 : 0xFF0000FF;
        }

        DynamicBackgroundView.fastSeparableBlur24x48(src, temp, dst, w, h, 2, 4);

        int mid = dst[(h / 2) * w + (w / 2)];
        int r = (mid >> 16) & 0xFF;
        int b = mid & 0xFF;
        assertTrue("Red channel should be blended around 127, got " + r, r > 100 && r < 155);
        assertTrue("Blue channel should be blended around 127, got " + b, b > 100 && b < 155);
    }
}
