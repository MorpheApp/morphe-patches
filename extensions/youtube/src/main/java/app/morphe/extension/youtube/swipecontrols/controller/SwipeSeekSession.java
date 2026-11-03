/*
 * Seek displacement adapted from Anil Beesetti's GPLv3 Next Player:
 * https://github.com/anilbeesetti/nextplayer/blob/06b3994b9f247a715d36c3efc7d0bf43567d80ba/feature/player/src/main/java/dev/anilbeesetti/nextplayer/feature/player/state/SeekGestureState.kt
 *
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.swipecontrols.controller;

import java.util.Objects;
import androidx.annotation.Nullable;

/** Seek target and cancellation state for a single horizontal gesture. */
public final class SwipeSeekSession {
    // Next Player sensitivity is 0.1-2.0, default 0.5. Store hundredths for the settings slider.
    public static final int MIN_SENSITIVITY_PERCENT = 10;
    public static final int MAX_SENSITIVITY_PERCENT = 200;
    public static final int DEFAULT_SENSITIVITY_PERCENT = 50;

    @Nullable
    private String videoId;
    private float startX;
    private long initial = -1;
    private long duration;
    private long target;

    public boolean isActive() {
        return initial >= 0;
    }

    public void begin(@Nullable String videoId, long position, long duration, float activationX) {
        reset();
        if (videoId == null || videoId.isEmpty() || position < 0 || duration <= 0 || !Float.isFinite(activationX)) return;
        this.videoId = videoId;
        this.startX = activationX;
        this.initial = Math.min(position, duration);
        this.duration = duration;
        this.target = this.initial;
    }

    /**
     * Next Player uses displacement * (sensitivity * 100) milliseconds. Normalize
     * pixels to dp so equal physical drags behave alike at different display densities.
     * The activation coordinate excludes the distance used to cross touch slop.
     */
    public void update(float positionX, float density, int sensitivityPercent) {
        if (!isActive() || density <= 0 || !Float.isFinite(density) || !Float.isFinite(positionX)) return;
        final int sensitivity = Math.max(MIN_SENSITIVITY_PERCENT, Math.min(MAX_SENSITIVITY_PERCENT, sensitivityPercent));
        final double distanceDp = ((double) positionX - startX) / density;
        final double requested = initial + distanceDp * sensitivity;
        target = (long) Math.max(0, Math.min(duration, requested));
    }

    public long getTarget() {
        return target;
    }

    public long getOffset() {
        return target - initial;
    }

    public boolean isCurrentVideo(@Nullable String currentVideoId) {
        return isActive() && Objects.equals(videoId, currentVideoId);
    }

    /** Returns a committed target, or -1 for cancellation, video changes, or no movement. */
    public long finish(boolean cancelled, @Nullable String currentVideoId) {
        final long result = !cancelled && isCurrentVideo(currentVideoId)
                && target != initial ? target : -1;
        reset();
        return result;
    }

    public void reset() {
        startX = 0;
        initial = -1;
        duration = 0;
        target = 0;
        videoId = null;
    }
}
