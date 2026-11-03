/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.swipecontrols.controller;

import java.util.Objects;

/** Seek target and cancellation state for a single horizontal gesture. */
public final class SwipeSeekSession {
    private String videoId;
    private long initial = -1;
    private long duration;
    private long target;

    public boolean isActive() {
        return initial >= 0;
    }

    public void begin(String videoId, long position, long duration) {
        reset();
        if (videoId == null || videoId.isEmpty() || position < 0 || duration <= 0) return;
        this.videoId = videoId;
        this.initial = Math.min(position, duration);
        this.duration = duration;
        this.target = this.initial;
    }

    public void update(float deltaX, int width, int sensitivitySeconds) {
        if (!isActive() || width <= 0 || !Float.isFinite(deltaX)) return;
        int sensitivity = Math.max(1, Math.min(120, sensitivitySeconds));
        double requested = initial + (double) deltaX / width * sensitivity * 1000;
        target = (long) Math.max(0, Math.min(duration, requested));
    }

    public long getTarget() {
        return target;
    }

    public long getDuration() {
        return duration;
    }

    public long getOffset() {
        return target - initial;
    }

    public boolean isCurrentVideo(String currentVideoId) {
        return isActive() && Objects.equals(videoId, currentVideoId);
    }

    /** Returns a committed target, or -1 for cancellation, video changes, or no movement. */
    public long finish(boolean cancelled, String currentVideoId) {
        long result = !cancelled && isCurrentVideo(currentVideoId)
                && target != initial ? target : -1;
        reset();
        return result;
    }

    public void reset() {
        initial = -1;
        duration = 0;
        target = 0;
        videoId = null;
    }
}
