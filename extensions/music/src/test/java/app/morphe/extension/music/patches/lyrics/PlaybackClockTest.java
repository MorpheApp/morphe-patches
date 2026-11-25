/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.media.session.PlaybackState;

import org.junit.Before;
import org.junit.Test;

public class PlaybackClockTest {

    private static final long NO_DURATION = Long.MAX_VALUE;

    private PlaybackClock clock;

    @Before
    public void setUp() {
        clock = new PlaybackClock();
        clock.reset(1_000L);
    }

    @Test
    public void pauseFreezesPositionAndIgnoresDelayedCoarsePauseState() {
        clock.applyPlaybackState(PlaybackState.STATE_PLAYING, 23_000L, 1f, 23_000L, 1_000L,
                NO_DURATION);
        assertEquals(23_000L, clock.expectedPositionAt(23_000L, 1_000L, NO_DURATION));
        assertEquals(24_000L, clock.expectedPositionAt(24_000L, 2_000L, NO_DURATION));

        clock.onPause(2_400L, NO_DURATION);
        final long pausedPos = clock.expectedPositionAt(24_000L, 2_400L, NO_DURATION);
        assertEquals(24_400L, pausedPos);
        assertEquals(24_400L, clock.expectedPositionAt(24_000L, 2_900L, NO_DURATION));
        assertEquals(24_400L, clock.expectedPositionAt(24_000L, 3_400L, NO_DURATION));

        clock.applyPlaybackState(PlaybackState.STATE_PAUSED, 23_100L, 0f, 23_100L, 3_430L,
                NO_DURATION);
        assertEquals(24_400L, clock.expectedPositionAt(23_100L, 3_500L, NO_DURATION));
    }

    @Test
    public void staleExtrapolationIsBoundedWhenSamplesStall() {
        clock.applyPlaybackState(PlaybackState.STATE_PLAYING, 10_000L, 1f, 10_000L, 1_000L,
                NO_DURATION);
        clock.expectedPositionAt(10_000L, 1_000L, NO_DURATION);
        long t = 1_000L;
        for (int i = 0; i < 16; i++) {
            t += 200L;
            clock.expectedPositionAt(10_000L, t, NO_DURATION);
        }
        final long stalledPos = clock.expectedPositionAt(10_000L, t + 200L, NO_DURATION);
        assertTrue("Position should cap at <= 11,600ms, was " + stalledPos,
                stalledPos <= 11_600L);
        assertTrue("Position should have advanced while within the stale budget, was " + stalledPos,
                stalledPos >= 11_400L);
    }

    @Test
    public void genuineSeekWhilePausedStillSnapsToNewPosition() {
        clock.applyPlaybackState(PlaybackState.STATE_PLAYING, 23_000L, 1f, 23_000L, 1_000L,
                NO_DURATION);
        clock.expectedPositionAt(24_000L, 2_000L, NO_DURATION);
        clock.onPause(2_400L, NO_DURATION);
        clock.applyPlaybackState(PlaybackState.STATE_PAUSED, 23_800L, 0f, 23_800L, 3_430L,
                NO_DURATION);
        assertEquals(24_400L, clock.expectedPositionAt(23_800L, 3_500L, NO_DURATION));

        clock.applyPlaybackState(PlaybackState.STATE_PAUSED, 15_000L, 0f, 15_000L, 4_000L,
                NO_DURATION);
        assertEquals(15_000L, clock.expectedPositionAt(15_000L, 4_050L, NO_DURATION));
        assertEquals(40_000L, clock.expectedPositionAt(40_000L, 4_500L, NO_DURATION));
    }

    @Test
    public void playResumesImmediatelyAndIgnoresCoarseLaggingPlayState() {
        clock.applyPlaybackState(PlaybackState.STATE_PAUSED, 20_000L, 0f, 20_000L, 1_000L,
                NO_DURATION);
        clock.onPlay(2_000L, NO_DURATION);
        assertEquals(20_400L, clock.expectedPositionAt(20_000L, 2_400L, NO_DURATION));
        clock.applyPlaybackState(PlaybackState.STATE_PLAYING, 19_800L, 1f, 19_800L, 2_800L,
                NO_DURATION);
        assertTrue(clock.expectedPositionAt(20_000L, 3_000L, NO_DURATION) >= 20_400L);
    }

    @Test
    public void laggingVideoTimeDuringPauseGraceDoesNotSnapBack() {
        clock.applyPlaybackState(PlaybackState.STATE_PLAYING, 23_000L, 1f, 23_000L, 1_000L,
                NO_DURATION);
        clock.expectedPositionAt(24_000L, 2_000L, NO_DURATION);
        clock.onPause(2_400L, NO_DURATION);
        assertEquals(24_400L, clock.expectedPositionAt(23_100L, 2_800L, NO_DURATION));
        assertEquals(18_000L, clock.expectedPositionAt(18_000L, 3_000L, NO_DURATION));
    }

    @Test
    public void mathSeamsMatchTheExtractedLogic() {
        assertEquals(24_000L, PlaybackClock.correctSmoothEstimate(23_000L, 24_000L));
        assertEquals(1.0f, PlaybackClock.calculateDriftSpeedFactor(0L), 0.001f);
        assertEquals(0.88f, PlaybackClock.calculateDriftSpeedFactor(-300L), 0.001f);
        assertEquals(1.18f, PlaybackClock.calculateDriftSpeedFactor(300L), 0.001f);
    }

    @Test
    public void trackDurationClampsExtrapolation() {
        clock.applyPlaybackState(PlaybackState.STATE_PLAYING, 10_000L, 1f, 10_000L, 1_000L,
                10_100L);
        assertEquals(10_100L, clock.expectedPositionAt(10_000L, 5_000L, 10_100L));
    }
}
