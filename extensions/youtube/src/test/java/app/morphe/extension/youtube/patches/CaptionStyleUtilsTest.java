/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */
package app.morphe.extension.youtube.patches;

import org.junit.Test;
import static org.junit.Assert.*;

public class CaptionStyleUtilsTest {
    @Test public void disabledStylingPreservesVideoAppearance() {
        assertEquals(0x12345678, CaptionStyleUtils.backgroundColor(0x12345678, false, false, true));
        assertFalse(CaptionStyleUtils.shouldBypassPen(false, true, false));
    }

    @Test public void nextPlayerControlsSelectBoldAndBackground() {
        assertEquals(0, CaptionStyleUtils.backgroundColor(123, true, false, false));
        assertEquals(0xFF000000, CaptionStyleUtils.backgroundColor(123, true, false, true));
        assertEquals(0, CaptionStyleUtils.fontStyle(false, false));
        assertEquals(1, CaptionStyleUtils.fontStyle(false, true));
        assertFalse(CaptionStyleUtils.shouldBypassPen(true, false, true));
        assertTrue(CaptionStyleUtils.shouldBypassPen(true, false, false));
    }

    @Test public void italicPresetOverridesBackgroundBoldAndCreatorPens() {
        assertEquals(0, CaptionStyleUtils.backgroundColor(123, true, true, true));
        assertEquals(3, CaptionStyleUtils.fontStyle(true, false));
        assertTrue(CaptionStyleUtils.shouldBypassPen(true, true, true));
    }
}
