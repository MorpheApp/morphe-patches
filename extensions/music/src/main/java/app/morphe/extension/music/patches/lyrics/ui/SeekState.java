/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

/** Position/highlight seeking state of the lyrics panel. */
final class SeekState {

    static final long BACKWARD_JUMP_MS = 600L;
    static final long RESTART_POSITION_MS = 200L;
    static final long FORWARD_JUMP_MS = 1000L;

    private static final long SEEK_SETTLE_MIN_MS = 150L;
    private static final long SEEK_SETTLE_MAX_MS = 1500L;
    private static final long SEEK_REACHED_BEFORE_MS = 200L;
    private static final long SEEK_REACHED_AFTER_MS = 2500L;

    private boolean seekPending;
    private long lastLivePosMs;
    private long pendingSeekPosMs = -1L;
    private long pendingSeekUptimeMs;
    private int pendingSeekMainIndex = -1;

    void reset() {
        lastLivePosMs = 0L;
        seekPending = false;
        pendingSeekPosMs = -1L;
        pendingSeekUptimeMs = 0L;
        pendingSeekMainIndex = -1;
    }

    void markSeekPending() {
        seekPending = true;
    }

    boolean isSeekPending() {
        return seekPending;
    }

    boolean consumeSeekPending() {
        final boolean wasPending = seekPending;
        seekPending = false;
        return wasPending;
    }

    long livePosition() {
        return lastLivePosMs;
    }

    void noteLivePosition(long posMs) {
        lastLivePosMs = posMs;
    }

    void requestTappedSeek(long lyricSeekPosMs, long nowUptimeMs, int mainIndex) {
        seekPending = true;
        pendingSeekPosMs = Math.max(0L, lyricSeekPosMs);
        pendingSeekUptimeMs = nowUptimeMs;
        pendingSeekMainIndex = mainIndex;
        lastLivePosMs = pendingSeekPosMs;
    }

    boolean hasPendingSeek() {
        return pendingSeekPosMs >= 0;
    }

    int pendingSeekMainIndex() {
        return pendingSeekMainIndex;
    }

    long resolvePosition(long rawPosMs, long nowUptimeMs) {
        if (!hasPendingSeek()) {
            return rawPosMs;
        }
        final long elapsed = nowUptimeMs - pendingSeekUptimeMs;
        final boolean reached = elapsed >= SEEK_SETTLE_MIN_MS
                && rawPosMs >= pendingSeekPosMs - SEEK_REACHED_BEFORE_MS
                && rawPosMs <= pendingSeekPosMs + SEEK_REACHED_AFTER_MS;
        if (reached || elapsed > SEEK_SETTLE_MAX_MS) {
            pendingSeekPosMs = -1L;
            pendingSeekMainIndex = -1;
            return rawPosMs;
        }
        return pendingSeekPosMs;
    }

    static boolean isInitialJump(long prevPosMs, long posMs) {
        return prevPosMs == 0 && posMs > 0;
    }

    static boolean isBackwardJump(long prevPosMs, long posMs) {
        return prevPosMs > 0 && (posMs < prevPosMs - BACKWARD_JUMP_MS
                || (posMs < RESTART_POSITION_MS && prevPosMs >= BACKWARD_JUMP_MS));
    }

    static boolean isForceJump(long prevPosMs, long posMs) {
        return prevPosMs > 0 && Math.abs(posMs - prevPosMs) > FORWARD_JUMP_MS;
    }

    static boolean crossedForwardJump(long prevPosMs, long posMs) {
        return posMs >= prevPosMs - FORWARD_JUMP_MS;
    }
}
