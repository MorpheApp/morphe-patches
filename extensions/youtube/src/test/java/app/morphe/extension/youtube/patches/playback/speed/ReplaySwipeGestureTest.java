/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5006
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.playback.speed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ReplaySwipeGestureTest {
    private final ReplaySwipeGesture gesture = new ReplaySwipeGesture();

    private void start(float x, float width) {
        gesture.start(x, 100, width, 8, 1000, 500);
    }

    @Test
    public void shortLeftDragSelectsOneSecond() {
        start(800, 1000);
        assertTrue(gesture.move(755, 100, 1010));
        assertEquals(1, gesture.getSeconds());
        assertEquals(1, gesture.finish());
    }

    @Test
    public void previewAdvancesFromOneToTenOverAThirdOfThePlayer() {
        start(600, 900);
        for (int seconds = 1; seconds <= 10; seconds++) {
            assertTrue(gesture.move(600 - seconds * 30, 100, 1000 + seconds * 10));
            assertEquals(seconds, gesture.getSeconds());
        }
        assertEquals(10, gesture.finish());
    }

    @Test
    public void dragCanStartAtTwoThirdsOfThePlayer() {
        start(600, 900);
        assertTrue(gesture.move(300, 100, 1010));
        assertEquals(10, gesture.finish());
    }

    @Test
    public void distanceScalesWithPlayerWidth() {
        start(450, 600);
        gesture.move(350, 100, 1010);
        assertEquals(5, gesture.finish());
        start(900, 1200);
        gesture.move(700, 100, 1010);
        assertEquals(5, gesture.finish());
    }

    @Test
    public void longerDragIsCappedAtTenSeconds() {
        start(800, 1000);
        gesture.move(-200, 100, 1010);
        assertEquals(10, gesture.finish());
    }

    @Test
    public void movingBackReducesTheSelectedRewind() {
        start(800, 1000);
        gesture.move(600, 100, 1010);
        assertEquals(6, gesture.getSeconds());
        gesture.move(700, 100, 1020);
        assertEquals(3, gesture.finish());
    }

    @Test
    public void returningToTheOriginCancelsWithoutReleasingTouchOwnership() {
        start(800, 1000);
        gesture.move(500, 100, 1010);
        assertTrue(gesture.move(798, 100, 1020));
        assertTrue(gesture.isSwiping());
        assertEquals(0, gesture.getSeconds());
        assertEquals(0, gesture.finish());
    }

    @Test
    public void tapAndTouchJitterAreNotSwipes() {
        start(800, 1000);
        assertFalse(gesture.move(795, 103, 1010));
        assertFalse(gesture.isSwiping());
        assertEquals(0, gesture.finish());
    }

    @Test
    public void rightwardGestureStaysWithThePlayer() {
        start(800, 1000);
        assertFalse(gesture.move(850, 100, 1010));
        assertFalse(gesture.move(400, 100, 1020));
        assertEquals(0, gesture.finish());
    }

    @Test
    public void verticalGestureIsNotTakenFromVolumeOrBrightness() {
        start(800, 1000);
        assertFalse(gesture.move(798, 130, 1010));
        assertFalse(gesture.move(500, 130, 1020));
        assertEquals(0, gesture.finish());
    }

    @Test
    public void ambiguousDiagonalDoesNotClaimUntilItIsClearlyHorizontal() {
        start(800, 1000);
        assertFalse(gesture.move(775, 120, 1010));
        assertTrue(gesture.move(700, 120, 1020));
        assertEquals(3, gesture.finish());
    }

    @Test
    public void longPressIsNotTakenFromTheExistingPlayerGesture() {
        start(800, 1000);
        assertFalse(gesture.move(600, 100, 1500));
        assertEquals(0, gesture.finish());
    }

    @Test
    public void holdingAnAlreadyClaimedSwipeDoesNotTurnItIntoALongPress() {
        start(800, 1000);
        assertTrue(gesture.move(600, 100, 1010));
        assertTrue(gesture.move(550, 100, 2500));
        assertEquals(8, gesture.finish());
    }

    @Test
    public void cancelDiscardsThePendingRewind() {
        start(800, 1000);
        gesture.move(400, 100, 1010);
        gesture.cancel();
        assertFalse(gesture.isSwiping());
        assertFalse(gesture.move(300, 100, 1020));
        assertEquals(0, gesture.finish());
    }

    @Test
    public void completedSwipeCanOnlyBeCommittedOnce() {
        start(800, 1000);
        gesture.move(600, 100, 1010);
        assertEquals(6, gesture.finish());
        assertEquals(0, gesture.finish());
    }

    @Test
    public void nextTouchStartsWithANewOriginAndNoOldPreview() {
        start(800, 1000);
        gesture.move(300, 100, 1010);
        start(400, 1000);
        assertEquals(0, gesture.getSeconds());
        assertFalse(gesture.isSwiping());
        gesture.move(370, 100, 1010);
        assertEquals(1, gesture.finish());
    }

    @Test
    public void missingPlayerBoundsCannotStartAGesture() {
        start(800, 0);
        assertFalse(gesture.move(600, 100, 1010));
        assertEquals(0, gesture.finish());
    }
}
