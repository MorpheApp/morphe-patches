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

public class SeekStateTest {

    @Test
    public void tappedLineOwnsPositionUntilPlayerReachesIt() {
        SeekState state = new SeekState();
        state.requestTappedSeek(30_000L, 1_000L, 7);
        assertTrue(state.hasPendingSeek());
        assertEquals(7, state.pendingSeekMainIndex());
        assertEquals(30_000L, state.livePosition());

        assertEquals(30_000L, state.resolvePosition(29_000L, 1_100L));
        assertEquals(7, state.pendingSeekMainIndex());

        assertEquals(30_100L, state.resolvePosition(30_100L, 1_200L));
        assertFalse(state.hasPendingSeek());
        assertEquals(-1, state.pendingSeekMainIndex());
    }

    @Test
    public void tappedLineWaitTimesOutWhenPlayerNeverArrives() {
        SeekState state = new SeekState();
        state.requestTappedSeek(30_000L, 1_000L, 3);
        assertEquals(1_000L, state.resolvePosition(1_000L, 3_000L));
        assertFalse(state.hasPendingSeek());
    }

    @Test
    public void coarseSeekFlagIsConsumedOnce() {
        SeekState state = new SeekState();
        state.markSeekPending();
        assertTrue(state.consumeSeekPending());
        assertFalse(state.isSeekPending());
        assertFalse(state.consumeSeekPending());
    }

    @Test
    public void jumpPredicatesKeepTheirOriginalThresholds() {
        assertFalse(SeekState.isInitialJump(0L, 0L));
        assertTrue(SeekState.isInitialJump(0L, 1L));
        assertTrue(SeekState.isBackwardJump(10_000L, 9_000L));
        assertTrue(SeekState.isBackwardJump(10_000L, 100L));
        assertFalse(SeekState.isBackwardJump(10_000L, 9_800L));
        assertTrue(SeekState.isForceJump(10_000L, 11_100L));
        assertFalse(SeekState.isForceJump(10_000L, 10_900L));
        assertTrue(SeekState.crossedForwardJump(10_000L, 9_100L));
        assertFalse(SeekState.crossedForwardJump(10_000L, 8_900L));
    }

    @Test
    public void resetClearsEverything() {
        SeekState state = new SeekState();
        state.requestTappedSeek(30_000L, 1_000L, 7);
        state.reset();
        assertFalse(state.hasPendingSeek());
        assertFalse(state.isSeekPending());
        assertEquals(0L, state.livePosition());
        assertEquals(-1, state.pendingSeekMainIndex());
    }
}
