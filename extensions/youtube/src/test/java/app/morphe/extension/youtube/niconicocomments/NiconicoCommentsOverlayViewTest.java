/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4816
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.niconicocomments;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class NiconicoCommentsOverlayViewTest {
    @Test
    public void pictureInPictureScalesWithTheWindow() {
        assertEquals(0.6f, NiconicoCommentsOverlayView.getPictureInPictureScale(648, 1080, 20), 0.001f);
        assertEquals(0.9f, NiconicoCommentsOverlayView.getPictureInPictureScale(972, 1080, 20), 0.001f);
        // An upright window can be longer than the player in portrait is wide.
        assertEquals(1f, NiconicoCommentsOverlayView.getPictureInPictureScale(1200, 1080, 20), 0);
    }

    @Test
    public void pictureInPictureKeepsSmallCommentsReadable() {
        // 20 sp comments are not drawn smaller than 10 sp.
        assertEquals(0.5f, NiconicoCommentsOverlayView.getPictureInPictureScale(324, 1080, 20), 0.001f);
        assertEquals(10f / 12, NiconicoCommentsOverlayView.getPictureInPictureScale(648, 1080, 12), 0.001f);
    }
}
