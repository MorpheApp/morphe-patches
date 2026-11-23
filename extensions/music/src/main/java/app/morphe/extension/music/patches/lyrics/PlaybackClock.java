/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics;

import android.media.session.PlaybackState;

/**
 * Estimates the playback position between the few samples a second delivered by
 * {@code PlaybackState} and {@code VideoInformation}. Pure state holder: every input is passed in,
 * there are no listeners, threads of its own, or Android framework reads. The owning
 * {@link LyricsManager} remains responsible for calling it on the main thread and for applying
 * the SkipSegments/offset adjustments to the result.
 */
final class PlaybackClock {

    /** A video time sample this far ahead of the estimate is a seek, which the estimate jumps to
     * instead of catching up with. So is a sample this far off after none came for a while. */
    private static final long SEEK_THRESHOLD_MS = 1000;
    /** A video time sample this far behind the estimate is a seek backwards. */
    private static final long BACKWARD_SEEK_THRESHOLD_MS = 500;
    /** While paused, a video time sample this far off replaces the estimate. */
    private static final long PAUSED_RESYNC_THRESHOLD_MS = 600;
    /** A playback state sample this far off the estimate is a seek. */
    private static final long PLAYBACK_STATE_SEEK_THRESHOLD_MS = 1200;
    /** Playback state samples older than this no longer vouch for the estimate. */
    private static final long PLAYBACK_STALE_MS = 500;
    /** A longer gap between two reads, such as after the app was in the background, is not
     * extrapolated over. */
    private static final long MAX_EXTRAPOLATION_MS = 500;
    /** Maximum duration to extrapolate forward without a confirming VideoInformation or
     * PlaybackState progression. Prevents multi-second word-sync overshoot when PlaybackState
     * lags behind audio pause. */
    private static final long MAX_STALE_EXTRAPOLATION_MS = 1600;
    /** Grace window for delayed coarse MediaSession STATE_PAUSED after MedialibPlayer.pauseVideo(). */
    private static final long PAUSE_STATE_GRACE_MS = 3500;
    /** Grace window for delayed coarse MediaSession STATE_PLAYING after MedialibPlayer.playVideo(). */
    private static final long PLAY_STATE_GRACE_MS = 2500;
    /** Extra tolerance for coarse MediaSession lag behind the frozen pause position during a
     * pause transition. */
    private static final long PAUSE_COARSE_LAG_TOLERANCE_MS = 1000;
    /** While playing, a video time sample this far ahead is jumped towards instead of caught up with. */
    private static final long VIDEO_TIME_CORRECTION_MS = 220;
    /** While playing, a playback state sample this far ahead is jumped towards instead of caught
     * up with. */
    private static final long PLAYBACK_STATE_CORRECTION_MS = 180;
    /** Drift within this is left alone. */
    private static final long DRIFT_DEADBAND_MS = 15;
    private static final float MIN_DRIFT_SPEED_FACTOR = 0.88f;
    private static final float MAX_DRIFT_SPEED_FACTOR = 1.18f;

    // The position is sampled a few times a second and estimated in between on every read,
    // so the highlight moves smoothly. Kept unrounded so extrapolating in small steps at a
    // playback speed other than 1 does not add up rounding errors.
    private double smoothPositionMs;
    private long lastSmoothUpdateUptimeMs;
    private long lastSampledPlaybackPosMs = -1;
    private long lastVideoTimeSample = -1;
    private long lastPlaybackSampleUptimeMs;
    private float playbackSpeed = 1f;
    private float driftSpeedFactor = 1f;
    /** Implicit state: PAUSED with a pending coarse STATE_PAUSED (PAUSE_GRACE) when true and
     * still inside {@link #PAUSE_STATE_GRACE_MS}. */
    private boolean awaitingPausedPlaybackState;
    private long pausedAtUptimeMs;
    /** Implicit state: PLAYING with a pending coarse STATE_PLAYING (RESUME_GRACE) when true and
     * still inside {@link #PLAY_STATE_GRACE_MS}. */
    private boolean awaitingResumedPlaybackState;
    private long resumedAtUptimeMs;
    private boolean playing;

    /** Carries the estimate forward by the time since it was last moved, while playing. */
    private void advanceSmoothPosition(long now, long durationMs) {
        if (playing && lastSmoothUpdateUptimeMs > 0 && now > lastSmoothUpdateUptimeMs) {
            final long maxUntil = lastPlaybackSampleUptimeMs > 0
                    ? lastPlaybackSampleUptimeMs + MAX_STALE_EXTRAPOLATION_MS
                    : Long.MAX_VALUE;
            final long effectiveEnd = Math.min(now, maxUntil);
            final long elapsed = Math.min(MAX_EXTRAPOLATION_MS,
                    Math.max(0L, effectiveEnd - lastSmoothUpdateUptimeMs));
            if (elapsed > 0) {
                final float effectiveSpeed = Math.max(0.1f, playbackSpeed * driftSpeedFactor);
                double advanced = smoothPositionMs + elapsed * (double) effectiveSpeed;
                advanced = Math.min(advanced, durationMs);
                smoothPositionMs = advanced;
            }
        }
        lastSmoothUpdateUptimeMs = now;
    }

    /** Pulls the estimate towards a sample, all the way when it is far off. */
    static long correctSmoothEstimate(long currentSmoothMs, long reportedPositionMs) {
        final double error = reportedPositionMs - currentSmoothMs;
        final double absError = Math.abs(error);
        final double factor = absError > 250 ? 1.0 : absError > 120 ? 0.45 : 0.20;
        return Math.max(0L, Math.round(currentSmoothMs + error * factor));
    }

    /** Proportional rate adjustment so smooth position catches up or slows down without step jumps. */
    static float calculateDriftSpeedFactor(long drift) {
        if (Math.abs(drift) <= DRIFT_DEADBAND_MS) {
            return 1.0f;
        }
        if (drift < 0L) {
            return Math.max(MIN_DRIFT_SPEED_FACTOR, 1.0f + (float) drift / 1000f);
        }
        return Math.min(MAX_DRIFT_SPEED_FACTOR, 1.0f + (float) drift / 800f);
    }

    private long smoothPosition() {
        return Math.round(smoothPositionMs);
    }

    void reset(long now) {
        smoothPositionMs = 0;
        lastSmoothUpdateUptimeMs = now;
        lastSampledPlaybackPosMs = -1;
        lastVideoTimeSample = -1;
        lastPlaybackSampleUptimeMs = 0;
        driftSpeedFactor = 1f;
        awaitingPausedPlaybackState = false;
        pausedAtUptimeMs = 0;
        awaitingResumedPlaybackState = false;
        resumedAtUptimeMs = 0;
    }

    /** Immediate pause: freezes extrapolation and arms the delayed coarse STATE_PAUSED grace. */
    void onPause(long now, long durationMs) {
        advanceSmoothPosition(now, durationMs);
        playing = false;
        driftSpeedFactor = 1f;
        awaitingPausedPlaybackState = true;
        pausedAtUptimeMs = now;
        awaitingResumedPlaybackState = false;
        resumedAtUptimeMs = 0;
    }

    /** Immediate resume: unfreezes extrapolation and arms the delayed coarse STATE_PLAYING grace. */
    void onPlay(long now, long durationMs) {
        advanceSmoothPosition(now, durationMs);
        playing = true;
        driftSpeedFactor = 1f;
        lastPlaybackSampleUptimeMs = now;
        awaitingResumedPlaybackState = true;
        resumedAtUptimeMs = now;
        awaitingPausedPlaybackState = false;
        pausedAtUptimeMs = 0;
    }

    /** Applies a coarse MediaSession state update, consuming the two grace windows. */
    void applyPlaybackState(int state, long newPosition, float speed, long videoTime,
                            long now, long durationMs) {
        final boolean wasPlaying = playing;
        final boolean nowPlaying = state == PlaybackState.STATE_PLAYING;
        // Up to now at the old state, so a pause keeps the time played since the last read.
        advanceSmoothPosition(now, durationMs);

        final boolean pauseGraceActive = awaitingPausedPlaybackState
                && !nowPlaying
                && (now - pausedAtUptimeMs) <= PAUSE_STATE_GRACE_MS;
        final boolean resumeGraceActive = awaitingResumedPlaybackState
                && nowPlaying
                && (now - resumedAtUptimeMs) <= PLAY_STATE_GRACE_MS;
        final boolean transitioned = (wasPlaying != nowPlaying)
                || pauseGraceActive
                || resumeGraceActive;

        awaitingPausedPlaybackState = false;
        awaitingResumedPlaybackState = false;

        if (wasPlaying != nowPlaying) {
            driftSpeedFactor = 1f;
        }
        playing = nowPlaying;
        // A new state can report zero before it reports where the video really is.
        final boolean isStaleZero = newPosition == 0 && videoTime > SEEK_THRESHOLD_MS;
        if (newPosition >= 0 && !isStaleZero) {
            final long smoothMs = smoothPosition();
            final long delta = newPosition - smoothMs;
            final boolean isGenuineTransitionSeek = !nowPlaying
                    ? (delta > PLAYBACK_STATE_SEEK_THRESHOLD_MS
                       || delta < -(PLAYBACK_STATE_SEEK_THRESHOLD_MS + PAUSE_COARSE_LAG_TOLERANCE_MS))
                    : Math.abs(delta) > PLAYBACK_STATE_SEEK_THRESHOLD_MS + PAUSE_COARSE_LAG_TOLERANCE_MS;
            if (lastSampledPlaybackPosMs < 0
                    || (!transitioned && Math.abs(delta) > PLAYBACK_STATE_SEEK_THRESHOLD_MS)
                    || (transitioned && isGenuineTransitionSeek)) {
                smoothPositionMs = newPosition;
            } else if (transitioned) {
                // The sample sent with a pause or a resume is coarse, and taking it would
                // make the highlight step back on pause or lag behind on resume.
                if (!nowPlaying && videoTime >= 0 && Math.abs(videoTime - smoothMs)
                        <= PLAYBACK_STATE_SEEK_THRESHOLD_MS + PAUSE_COARSE_LAG_TOLERANCE_MS) {
                    // Consume the matching lagging VideoInformation timestamp so the next
                    // expectedPositionAt() tick does not re-trigger isPausedSeek.
                    lastVideoTimeSample = videoTime;
                }
            } else if (!nowPlaying && Math.abs(delta) > PAUSED_RESYNC_THRESHOLD_MS) {
                smoothPositionMs = newPosition;
            } else if (nowPlaying && newPosition != lastSampledPlaybackPosMs) {
                // While playing, a coarse sample may only pull the estimate forward, unless it
                // is the only source of the position.
                if (videoTime < 0 || delta > PLAYBACK_STATE_CORRECTION_MS) {
                    smoothPositionMs = correctSmoothEstimate(smoothMs, newPosition);
                } else {
                    driftSpeedFactor = calculateDriftSpeedFactor(delta);
                }
            }
            lastSampledPlaybackPosMs = newPosition;
            lastPlaybackSampleUptimeMs = now;
        }

        // A paused state reports speed zero, which would freeze the extrapolation even
        // after playback resumes, so only positive speeds are kept.
        if (speed > 0) {
            playbackSpeed = speed;
        }
    }

    /** Advances and re-anchors the estimate from a VideoInformation sample. */
    long expectedPositionAt(long videoTime, long now, long durationMs) {
        advanceSmoothPosition(now, durationMs);

        if (videoTime >= 0 && videoTime != lastVideoTimeSample) {
            lastVideoTimeSample = videoTime;

            final long drift = videoTime - smoothPosition();
            final boolean isInitial = lastPlaybackSampleUptimeMs == 0 || smoothPositionMs == 0;
            final boolean isBackwardSeek = playing && smoothPositionMs > 0
                    && drift < -BACKWARD_SEEK_THRESHOLD_MS;
            final boolean isForwardSeek = drift > SEEK_THRESHOLD_MS;
            final boolean isStaleDrift = playing && Math.abs(drift) > SEEK_THRESHOLD_MS
                    && now - lastPlaybackSampleUptimeMs > PLAYBACK_STALE_MS;
            final boolean inPauseGrace = !playing && awaitingPausedPlaybackState
                    && (now - pausedAtUptimeMs) <= PAUSE_STATE_GRACE_MS;
            final boolean isPausedSeek = !playing && (inPauseGrace
                    ? (drift > PAUSED_RESYNC_THRESHOLD_MS
                       || drift < -(PLAYBACK_STATE_SEEK_THRESHOLD_MS + PAUSE_COARSE_LAG_TOLERANCE_MS))
                    : Math.abs(drift) > PAUSED_RESYNC_THRESHOLD_MS);

            if (isInitial || isBackwardSeek || isForwardSeek || isStaleDrift || isPausedSeek) {
                smoothPositionMs = videoTime;
                driftSpeedFactor = 1f;
                awaitingPausedPlaybackState = false;
                awaitingResumedPlaybackState = false;
            } else if (playing) {
                if (drift > VIDEO_TIME_CORRECTION_MS) {
                    final long corrected = correctSmoothEstimate(smoothPosition(), videoTime);
                    smoothPositionMs = corrected;
                    driftSpeedFactor = calculateDriftSpeedFactor(videoTime - corrected);
                } else {
                    driftSpeedFactor = calculateDriftSpeedFactor(drift);
                }
            }
            lastPlaybackSampleUptimeMs = now;
        }

        return Math.max(0L, smoothPosition());
    }

    boolean isPlaying() {
        return playing;
    }

    float getPlaybackSpeed() {
        return playbackSpeed > 0f ? playbackSpeed : 1f;
    }

}
