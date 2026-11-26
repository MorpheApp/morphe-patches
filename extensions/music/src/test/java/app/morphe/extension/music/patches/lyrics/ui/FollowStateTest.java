/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class FollowStateTest {

    @Test
    public void userScrollStartsBrowsingAndExpiresAfterGrace() {
        FollowState state = new FollowState();
        state.noteUserScroll(5_000L);
        assertTrue(state.isBrowsing());
        assertTrue(state.isUserScrolling(1_000L));
        assertTrue(state.hasUserScrollGrace(4_999L));
        assertFalse(state.hasUserScrollGrace(5_000L));
        assertFalse(state.hasUserScrollGrace(5_001L));

        assertTrue(state.isUserScrolling(9_000L));
    }

    @Test
    public void exitBrowsingClearsTheWholeCluster() {
        FollowState state = new FollowState();
        state.noteUserScroll(5_000L);
        state.setDraggingScroll(true);
        state.setLastScrollTarget(12);
        state.exitBrowsing();

        assertFalse(state.isBrowsing());
        assertFalse(state.isDraggingScroll());
        assertFalse(state.hasUserScrollGrace(1_000L));
        assertEquals(-1, state.lastScrollTarget());
        assertFalse(state.isUserScrolling(1_000L));
    }

    @Test
    public void draggingAndProgrammaticFlagsAreIndependent() {
        FollowState state = new FollowState();
        state.setDraggingScroll(true);
        assertTrue(state.isUserScrolling(0L));
        state.setProgrammaticScrolling(true);
        assertTrue(state.isProgrammaticScrolling());
        state.setProgrammaticScrolling(false);
        assertFalse(state.isProgrammaticScrolling());
    }
}
