/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5006
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.playback.speed;

/** A single slow replay, driven by the player's position rather than elapsed playback time. */
public final class ReplayVideoController {
    public interface Player {
        String videoId();
        long position();
        float speed();
        long elapsedRealtime();
        boolean seekTo(long position);
        void changeSpeed(float speed);
    }

    /**
     * How long to wait for the player to report the position after the rewind.
     */
    private static final long SEEK_TIMEOUT_MS = 5000;
    /**
     * Check interval while waiting for the rewind to land.
     */
    private static final long SEEK_CHECK_INTERVAL_MS = 10;
    /**
     * Longest check interval while the slowed down part plays.
     */
    private static final long MAX_CHECK_INTERVAL_MS = 100;

    private final Player player;
    private volatile boolean active;
    private boolean applyingChange;
    private boolean rewindObserved;
    private String videoId = "";
    private long rewindFromPosition;
    private long slowDurationMs;
    private long returnPosition;
    private long seekStarted;
    private float originalSpeed;
    private float replaySpeed;

    public ReplayVideoController(Player player) {
        this.player = player;
    }

    public boolean isActive() {
        return active;
    }

    public boolean start(long rewindMs, long slowPlaybackMs, float requestedSpeed) {
        if (active || rewindMs <= 0 || slowPlaybackMs <= 0 || !validSpeed(requestedSpeed)) return false;

        final long position = player.position();
        final float speed = player.speed();
        String id = player.videoId();
        if (position <= 0 || id.isEmpty() || !validSpeed(speed)) return false;

        videoId = id;
        rewindFromPosition = position;
        slowDurationMs = slowPlaybackMs;
        originalSpeed = speed;
        // A replay must not speed up a video that is already slower than the setting.
        replaySpeed = Math.min(requestedSpeed, speed);
        seekStarted = player.elapsedRealtime();
        rewindObserved = false;
        active = true;
        applyingChange = true;
        try {
            if (!player.seekTo(Math.max(0, position - rewindMs))) {
                active = false;
                return false;
            }
            player.changeSpeed(replaySpeed);
            if (player.speed() != replaySpeed) {
                cancel(true);
                return false;
            }
            observeRewind(player.position());
            return true;
        } catch (RuntimeException ex) {
            cancel(true);
            throw ex;
        } finally {
            applyingChange = false;
        }
    }

    public void tick() {
        if (!active) return;
        if (!videoId.equals(player.videoId()) || player.speed() != replaySpeed) {
            cancel(false);
            return;
        }

        final long position = player.position();
        if (position < 0) return;
        if (!rewindObserved) {
            // A successful seek can return before the player publishes its new position.
            // Do not finish the replay on a stale sample of the original position.
            if (position < rewindFromPosition) {
                observeRewind(position);
            } else if (player.elapsedRealtime() - seekStarted >= SEEK_TIMEOUT_MS) {
                cancel(true);
            }
            return;
        }
        if (position >= returnPosition) cancel(true);
    }

    private void observeRewind(long position) {
        if (position >= 0 && position < rewindFromPosition) {
            rewindObserved = true;
            // Start at the actual seek landing point, including near the beginning of a video.
            returnPosition = position + slowDurationMs;
        }
    }

    /** Check more closely while the seek is pending or the return position is near. */
    public long nextCheckDelayMillis() {
        if (!rewindObserved) return SEEK_CHECK_INTERVAL_MS;
        final long untilReturn = (long) Math.ceil((returnPosition - player.position()) / (double) replaySpeed);
        return Math.max(1, Math.min(MAX_CHECK_INTERVAL_MS, untilReturn));
    }

    /** Called before a native seek; our own initial seek is deliberately ignored. */
    public void onSeek() {
        if (!applyingChange) cancel(true);
    }

    /** A manual selection wins, even when the user selects the temporary replay speed. */
    public void onUserSelectedSpeed() {
        if (!applyingChange) cancel(false);
    }

    public void onSpeedChanged(float speed) {
        if (!applyingChange && active && speed != replaySpeed) cancel(false);
    }

    public void cancel(boolean restoreSpeed) {
        if (!active) return;
        active = false;
        if (!restoreSpeed || !videoId.equals(player.videoId()) || player.speed() != replaySpeed) return;

        final boolean wasApplyingChange = applyingChange;
        applyingChange = true;
        try {
            player.changeSpeed(originalSpeed);
        } finally {
            applyingChange = wasApplyingChange;
        }
    }

    private static boolean validSpeed(float speed) {
        return speed > 0 && !Float.isInfinite(speed);
    }
}
