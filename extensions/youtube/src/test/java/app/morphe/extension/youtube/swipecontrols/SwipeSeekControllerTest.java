/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4364
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.swipecontrols;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.youtube.swipecontrols.controller.SeekbarScrubber;
import app.morphe.extension.youtube.swipecontrols.controller.SwipeSeekController;

public class SwipeSeekControllerTest {

    /**
     * Time bar from x 0 to 1000, of a video 10 seconds long.
     */
    private static final class FakeScrubber implements SeekbarScrubber {
        static final int WIDTH = 1000;

        final int startX;
        final List<String> moves = new ArrayList<>();
        int completedX = -1;

        FakeScrubber(int startX) {
            this.startX = startX;
        }

        @Override
        public int patch_startScrub() {
            return startX;
        }

        @Override
        public long patch_moveScrub(int x) {
            moves.add(String.valueOf(x));
            return Math.max(0, Math.min(WIDTH, x)) * 10L;
        }

        @Override
        public void patch_completeScrub(int x) {
            completedX = x;
        }
    }

    private static SwipeSeekController startedWith(FakeScrubber scrubber, float sensitivity) {
        SwipeSeekController.setScrubber(scrubber);
        SwipeSeekController seek = new SwipeSeekController();
        assertTrue(seek.start(sensitivity));
        // Start reads the time at the start position.
        scrubber.moves.clear();
        return seek;
    }

    @Test
    public void scrubberFollowsTheSwipeAndSeeksOnRelease() {
        FakeScrubber scrubber = new FakeScrubber(100);
        SwipeSeekController seek = startedWith(scrubber, 1);
        assertTrue(seek.isScrubbing());
        assertTrue(SwipeSeekController.isSwipeSeeking());

        seek.move(10.4f);
        seek.move(10.4f);
        seek.move(-30f);
        seek.end(false);

        assertEquals(List.of("110", "121", "91"), scrubber.moves);
        assertEquals(91, scrubber.completedX);
        assertFalse(seek.isScrubbing());
        assertFalse(SwipeSeekController.isSwipeSeeking());
    }

    @Test
    public void sensitivityScalesTheSwipe() {
        FakeScrubber faster = new FakeScrubber(100);
        SwipeSeekController seek = startedWith(faster, 2);
        seek.move(50);
        seek.end(false);
        assertEquals(200, faster.completedX);

        FakeScrubber slower = new FakeScrubber(100);
        seek = startedWith(slower, 0.5f);
        seek.move(50);
        seek.end(false);
        assertEquals(125, slower.completedX);
    }

    @Test
    public void slowSmallMovementsAddUp() {
        FakeScrubber scrubber = new FakeScrubber(500);
        SwipeSeekController seek = startedWith(scrubber, 0.5f);

        for (int i = 0; i < 20; i++) {
            seek.move(1);
        }
        seek.end(false);

        assertEquals(510, scrubber.completedX);
    }

    @Test
    public void swipingBackFromPastTheEndMovesRightAway() {
        FakeScrubber scrubber = new FakeScrubber(980);
        SwipeSeekController seek = startedWith(scrubber, 1);

        for (int i = 0; i < 10; i++) {
            seek.move(30);
        }
        // The first step back moves the scrubber away from the end.
        seek.move(-30);
        seek.end(false);

        assertTrue(scrubber.completedX < FakeScrubber.WIDTH);
        assertTrue(scrubber.completedX >= FakeScrubber.WIDTH - 30);
    }

    @Test
    public void swipingBackFromPastTheStartMovesRightAway() {
        FakeScrubber scrubber = new FakeScrubber(20);
        SwipeSeekController seek = startedWith(scrubber, 1);

        for (int i = 0; i < 10; i++) {
            seek.move(-30);
        }
        seek.move(30);
        seek.end(false);

        assertTrue(scrubber.completedX > 0);
        assertTrue(scrubber.completedX <= 30);
    }

    @Test
    public void cancelReturnsToTheStart() {
        FakeScrubber scrubber = new FakeScrubber(200);
        SwipeSeekController seek = startedWith(scrubber, 1);

        seek.move(50);
        seek.end(true);

        assertEquals(List.of("250"), scrubber.moves);
        assertEquals(200, scrubber.completedX);
    }
}
