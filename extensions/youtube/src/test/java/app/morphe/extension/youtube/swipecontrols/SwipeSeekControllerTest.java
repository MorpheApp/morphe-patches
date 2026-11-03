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

    private static final class FakeScrubber implements SeekbarScrubber {
        final int startX;
        final List<String> calls = new ArrayList<>();

        FakeScrubber(int startX) {
            this.startX = startX;
        }

        @Override
        public int patch_startScrub() {
            calls.add("start");
            return startX;
        }

        @Override
        public void patch_moveScrub(int x) {
            calls.add("move " + x);
        }

        @Override
        public void patch_completeScrub(int x) {
            calls.add("complete " + x);
        }
    }

    private static SwipeSeekController startedWith(FakeScrubber scrubber) {
        SwipeSeekController.setScrubber(scrubber);
        SwipeSeekController seek = new SwipeSeekController();
        assertTrue(seek.start());
        return seek;
    }

    @Test
    public void scrubberFollowsTheSwipeAndSeeksOnRelease() {
        FakeScrubber scrubber = new FakeScrubber(100);
        SwipeSeekController seek = startedWith(scrubber);
        assertTrue(seek.isScrubbing());
        assertTrue(SwipeSeekController.isSwipeSeeking());

        seek.move(10.4f);
        seek.move(10.4f);
        seek.move(-30f);
        seek.end(false);

        assertEquals(List.of("start", "move 110", "move 121", "move 91", "complete 91"), scrubber.calls);
        assertFalse(seek.isScrubbing());
        assertFalse(SwipeSeekController.isSwipeSeeking());
    }

    @Test
    public void smallMovementsAddUpWithoutRepeatingAPosition() {
        FakeScrubber scrubber = new FakeScrubber(0);
        SwipeSeekController seek = startedWith(scrubber);

        for (int i = 0; i < 4; i++) {
            seek.move(0.25f);
        }
        seek.end(false);

        assertEquals(List.of("start", "move 1", "complete 1"), scrubber.calls);
    }

    @Test
    public void cancelReturnsToTheStart() {
        FakeScrubber scrubber = new FakeScrubber(200);
        SwipeSeekController seek = startedWith(scrubber);

        seek.move(50);
        seek.end(true);

        assertEquals(List.of("start", "move 250", "complete 200"), scrubber.calls);
    }
}
