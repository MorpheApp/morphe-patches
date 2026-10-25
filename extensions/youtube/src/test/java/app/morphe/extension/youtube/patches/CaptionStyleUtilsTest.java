/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class CaptionStyleUtilsTest {
    private static final int FALLBACK = 0x12345678;

    @Test public void parsesOpaqueColor() {
        assertEquals(0xFFFF8800, CaptionStyleUtils.parseColor("#FF8800", FALLBACK));
        assertEquals(0xFFFF8800, CaptionStyleUtils.parseColor("FF8800", FALLBACK));
    }

    @Test public void parsesColorWithAlpha() {
        assertEquals(0xBF000000, CaptionStyleUtils.parseColor("#BF000000", FALLBACK));
        assertEquals(0x00000000, CaptionStyleUtils.parseColor("#00000000", FALLBACK));
        assertEquals(0x80FFFFFF, CaptionStyleUtils.parseColor("#80ffffff", FALLBACK));
    }

    @Test public void trimsWhitespace() {
        assertEquals(0xFF112233, CaptionStyleUtils.parseColor("  #112233 ", FALLBACK));
    }

    @Test public void invalidColorUsesFallback() {
        assertEquals(FALLBACK, CaptionStyleUtils.parseColor(null, FALLBACK));
        assertEquals(FALLBACK, CaptionStyleUtils.parseColor("", FALLBACK));
        assertEquals(FALLBACK, CaptionStyleUtils.parseColor("#FFF", FALLBACK));
        assertEquals(FALLBACK, CaptionStyleUtils.parseColor("#GGGGGG", FALLBACK));
        assertEquals(FALLBACK, CaptionStyleUtils.parseColor("#FF00000000", FALLBACK));
    }

    @Test public void scalesFontScale() {
        assertEquals(1.0f, CaptionStyleUtils.scaleFontScale(1.0f, 100), 0.0001f);
        assertEquals(2.0f, CaptionStyleUtils.scaleFontScale(1.0f, 200), 0.0001f);
        assertEquals(1.5f, CaptionStyleUtils.scaleFontScale(1.0f, 150), 0.0001f);
        assertEquals(0.75f, CaptionStyleUtils.scaleFontScale(1.5f, 50), 0.0001f);
    }

    @Test public void clampsFontScale() {
        assertEquals(0.25f, CaptionStyleUtils.scaleFontScale(1.0f, 0), 0.0001f);
        assertEquals(0.25f, CaptionStyleUtils.scaleFontScale(1.0f, -50), 0.0001f);
        assertEquals(4.0f, CaptionStyleUtils.scaleFontScale(1.0f, 9999), 0.0001f);
    }
}
