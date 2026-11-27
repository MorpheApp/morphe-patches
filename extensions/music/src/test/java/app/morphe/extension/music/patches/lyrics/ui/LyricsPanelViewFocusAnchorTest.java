/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class LyricsPanelViewFocusAnchorTest {

    @Test
    public void baseAnchorIsUsedWhenPlayedLinesAreShown() {
        assertEquals(80, LyricsPanelView.resolveFocusAnchorPx(800, false, 80, 36));
        assertEquals(80, LyricsPanelView.resolveFocusAnchorPx(0, true, 80, 36));
    }

    @Test
    public void hidePlayedMovesAnchorLowerThanBase() {
        int focus = LyricsPanelView.resolveFocusAnchorPx(1000, true, 80, 36);
        assertEquals(450, focus, 1);
        org.junit.Assert.assertTrue(focus > 80);
    }

    @Test
    public void hidePlayedAnchorRespectsMinimum() {
        assertEquals(36, LyricsPanelView.resolveFocusAnchorPx(20, true, 12, 36));
    }
}
