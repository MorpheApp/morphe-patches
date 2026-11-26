/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4866
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.media.session.PlaybackState;

import org.junit.Before;
import org.junit.Test;

/**
 * Verifies immediate pause freeze, bounded stale extrapolation, and coarse delayed
 * {@code STATE_PAUSED} anti-snapback behavior in {@link LyricsManager}.
 */
public class LyricsManagerPauseSyncTest {

    private LyricsManager manager;

    @Before
    public void setUp() {
        LyricsTestEnv.bootstrap();
        manager = LyricsManager.getInstance();
        manager.resetPositionAt(1_000L);
    }

    @Test
    public void onPauseVideoImmediatelyFreezesPositionAndIgnoresDelayedCoarsePauseState() {
        // Playing at 23,000 ms, t = 1,000.
        manager.applyPlaybackState(PlaybackState.STATE_PLAYING, 23_000L, 1f, 23_000L, 1_000L);
        assertEquals(23_000L, manager.computeExpectedPositionAt(23_000L, 1_000L));
        assertEquals(24_000L, manager.computeExpectedPositionAt(24_000L, 2_000L));

        // Audio pauses at t = 2,400 -> real position is 24,400 ms (within MAX_EXTRAPOLATION_MS step).
        manager.onPauseVideoAt(2_400L);
        final long pausedPos = manager.computeExpectedPositionAt(24_000L, 2_400L);
        assertEquals(24_400L, pausedPos);

        // Frame ticks keep firing for +1s while MediaSession hasn't updated yet: must stay frozen!
        assertEquals(24_400L, manager.computeExpectedPositionAt(24_000L, 2_900L));
        assertEquals(24_400L, manager.computeExpectedPositionAt(24_000L, 3_400L));

        // ~1.03s later, YouTube Music 9.30.53 finally delivers delayed coarse STATE_PAUSED with lagging position (23,100 ms).
        manager.applyPlaybackState(PlaybackState.STATE_PAUSED, 23_100L, 0f, 23_100L, 3_430L);
        assertEquals(24_400L, manager.computeExpectedPositionAt(23_100L, 3_500L));
    }

    @Test
    public void staleExtrapolationIsBoundedWhenVideoTimeAndPlaybackStateStall() {
        manager.applyPlaybackState(PlaybackState.STATE_PLAYING, 10_000L, 1f, 10_000L, 1_000L);
        manager.computeExpectedPositionAt(10_000L, 1_000L);

        // Step for 3,200 ms without any VideoInformation or PlaybackState progression.
        long t = 1_000L;
        for (int i = 0; i < 16; i++) {
            t += 200L;
            manager.computeExpectedPositionAt(10_000L, t);
        }
        final long stalledPos = manager.computeExpectedPositionAt(10_000L, t + 200L);
        assertTrue("Position should cap at <= 11,600ms after 1.6s stale budget, was " + stalledPos,
                stalledPos <= 11_600L);
        assertTrue("Position should have advanced while within stale budget, was " + stalledPos,
                stalledPos >= 11_400L);
    }

    @Test
    public void genuineSeekWhilePausedStillSnapsToNewPosition() {
        manager.applyPlaybackState(PlaybackState.STATE_PLAYING, 23_000L, 1f, 23_000L, 1_000L);
        manager.computeExpectedPositionAt(23_000L, 1_000L);
        assertEquals(24_000L, manager.computeExpectedPositionAt(24_000L, 2_000L));

        // User pauses, delayed coarse STATE_PAUSED arrives and is ignored.
        manager.onPauseVideoAt(2_400L);
        assertEquals(24_400L, manager.computeExpectedPositionAt(24_000L, 2_400L));
        manager.applyPlaybackState(PlaybackState.STATE_PAUSED, 23_800L, 0f, 23_800L, 3_430L);
        assertEquals(24_400L, manager.computeExpectedPositionAt(23_800L, 3_500L));

        // Genuine backward seek to 15,000 ms while paused snaps immediately.
        manager.applyPlaybackState(PlaybackState.STATE_PAUSED, 15_000L, 0f, 15_000L, 4_000L);
        assertEquals(15_000L, manager.computeExpectedPositionAt(15_000L, 4_050L));

        // Genuine forward seek to 40,000 ms via VideoInformation while paused also snaps.
        assertEquals(40_000L, manager.computeExpectedPositionAt(40_000L, 4_500L));
    }

    @Test
    public void onPlayVideoImmediatelyResumesAndIgnoresCoarseLaggingPlayState() {
        manager.applyPlaybackState(PlaybackState.STATE_PAUSED, 20_000L, 0f, 20_000L, 1_000L);
        manager.onPlayVideoAt(2_000L);

        assertEquals(20_400L, manager.computeExpectedPositionAt(20_000L, 2_400L));

        // Delayed STATE_PLAYING with slightly stale 19,800 ms position shouldn't snap backward.
        manager.applyPlaybackState(PlaybackState.STATE_PLAYING, 19_800L, 1f, 19_800L, 2_800L);
        final long posAfterPlayState = manager.computeExpectedPositionAt(20_000L, 3_000L);
        assertTrue("Position should remain >= 20,400ms after resume, was " + posAfterPlayState,
                posAfterPlayState >= 20_400L);
    }

    @Test
    public void laggingVideoTimeDuringPauseGraceDoesNotSnapBackBeforePlaybackStateArrives() {
        manager.applyPlaybackState(PlaybackState.STATE_PLAYING, 23_000L, 1f, 23_000L, 1_000L);
        manager.computeExpectedPositionAt(23_000L, 1_000L);
        assertEquals(24_000L, manager.computeExpectedPositionAt(24_000L, 2_000L));

        // Pause at t = 2,400 (frozen at 24,400 ms).
        manager.onPauseVideoAt(2_400L);
        assertEquals(24_400L, manager.computeExpectedPositionAt(24_000L, 2_400L));

        // Coarse VideoInformation update at -1,300 ms behind frozen position arrives BEFORE STATE_PAUSED.
        // Must be ignored during pause grace rather than snapped by isBackwardSeek.
        assertEquals(24_400L, manager.computeExpectedPositionAt(23_100L, 2_800L));

        // Genuine large seek > 2,200 ms behind frozen position during pause grace still snaps.
        assertEquals(18_000L, manager.computeExpectedPositionAt(18_000L, 3_000L));
    }
}
