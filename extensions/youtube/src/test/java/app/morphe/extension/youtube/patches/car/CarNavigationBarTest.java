/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4933
 *
 * Adapted from
 * https://codeberg.org/JohnInNL/nl-morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches.car;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Exercises the swipe recognition that shows the hidden navigation bar again. */
public class CarNavigationBarTest {
    private static final float DENSITY = 2.5f;

    @Test
    public void isSwipeUp_acceptsASwipeUpFromTheBottomEdge() {
        // 24dp up at this density is 60px.
        assertTrue(CarNavigationBar.isSwipeUp(60, 10, DENSITY));
        assertTrue(CarNavigationBar.isSwipeUp(120, 60, DENSITY));
    }

    @Test
    public void isSwipeUp_rejectsShorterOrSidewaysMovements() {
        // Too short to be intentional.
        assertFalse(CarNavigationBar.isSwipeUp(59, 0, DENSITY));
        // Mostly sideways, such as scrolling the feed.
        assertFalse(CarNavigationBar.isSwipeUp(60, 61, DENSITY));
        // Downward movement.
        assertFalse(CarNavigationBar.isSwipeUp(-60, 0, DENSITY));
    }
}
