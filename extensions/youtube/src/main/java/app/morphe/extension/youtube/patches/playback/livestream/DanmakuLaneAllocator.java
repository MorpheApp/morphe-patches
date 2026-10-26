/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.playback.livestream;

/**
 * Pure lane-allocation logic for danmaku, with no Android dependencies.
 *
 * Niconico-style model: every comment takes the SAME time to cross the
 * screen, measured from its head entering at the right edge to its tail
 * leaving at the left edge. The crossing time is set directly in seconds
 * by the user. A longer comment therefore moves FASTER:
 * velocity = (container width + text width) / crossing duration.
 *
 * To keep comments in the same lane from overlapping, each lane remembers
 * its last comment (start time, text width, velocity). A new comment may
 * enter a lane only if BOTH of these hold:
 *
 * 1. Gap: the previous comment's tail (right end) has moved at least
 *    {@code minGapPx} to the left of the container's right edge.
 * 2. No catch-up: if the new comment is faster than the previous one, it
 *    still must not reach the previous comment's tail before that tail
 *    leaves the left edge. This is checked at the moment the previous
 *    tail exits: the new comment's head must still be at or to the right
 *    of the left edge at that moment. Because the relative speed is
 *    constant, the gap between the two only shrinks over time, so if
 *    they are still separated when the previous comment disappears,
 *    they never touched.
 *
 * If no lane satisfies both, {@link #chooseLane} returns -1 and the
 * caller is expected to let the comment wait (see DanmakuOverlay's
 * waiting queue) instead of forcing it into a lane.
 */
public final class DanmakuLaneAllocator {

    /**
     * Default time for one comment to cross the screen (head in at the
     * right edge to tail out at the left edge). The same for every
     * comment, whatever its length.
     */
    public static final long BASE_CROSSING_MS = 10_000L;

    private static final int MAX_LANES = 30;

    /** Start time of the last comment placed in each lane, 0 if none. */
    private final long[] laneStartMs = new long[MAX_LANES];
    /** Text width of the last comment placed in each lane. */
    private final int[] laneTextWidthPx = new int[MAX_LANES];
    /** Velocity of the last comment placed in each lane, px per ms. */
    private final double[] laneVelocityPxPerMs = new double[MAX_LANES];

    private int maxRows;

    public DanmakuLaneAllocator(int maxRows) {
        this.maxRows = clampRows(maxRows);
        reset();
    }

    public static int clampRows(int rows) {
        return Math.max(1, Math.min(MAX_LANES, rows));
    }

    public static int clampCrossingSeconds(int crossingSeconds) {
        return Math.max(4, Math.min(20, crossingSeconds));
    }

    public static int clampOpacityPercent(int opacityPercent) {
        return Math.max(20, Math.min(100, opacityPercent));
    }

    /** Crossing duration for every comment, in the user-set seconds. */
    public static long crossingDurationMs(int crossingSeconds) {
        return clampCrossingSeconds(crossingSeconds) * 1000L;
    }

    /**
     * Pixels per millisecond for one comment. Longer comments are faster,
     * because every comment crosses in {@link #crossingDurationMs}.
     */
    public static double velocityPxPerMs(int containerWidthPx, int textWidthPx, int crossingSeconds) {
        if (containerWidthPx <= 0) {
            return 0;
        }
        return (double) (containerWidthPx + Math.max(0, textWidthPx))
                / crossingDurationMs(crossingSeconds);
    }

    /**
     * Total time for a comment to travel from the right edge to
     * off-screen left. The same for every comment at a given setting.
     */
    public static long travelTimeMs(int containerWidthPx, int textWidthPx, int crossingSeconds) {
        if (containerWidthPx <= 0) {
            return BASE_CROSSING_MS;
        }
        return crossingDurationMs(crossingSeconds);
    }

    public void setMaxRows(int maxRows) {
        this.maxRows = clampRows(maxRows);
    }

    public int getMaxRows() {
        return maxRows;
    }

    public void reset() {
        for (int i = 0; i < MAX_LANES; i++) {
            laneStartMs[i] = 0;
            laneTextWidthPx[i] = 0;
            laneVelocityPxPerMs[i] = 0;
        }
    }

    /**
     * Position of a comment's tail (right end) at {@code nowMs}, in
     * container coordinates. Starts at container width + text width and
     * moves left; it is off-screen once this reaches 0.
     */
    private static double tailPositionPx(int containerWidthPx, int textWidthPx,
                                         double velocityPxPerMs, long startMs, long nowMs) {
        return containerWidthPx + textWidthPx - velocityPxPerMs * (nowMs - startMs);
    }

    /**
     * Try to place a comment, checking the gap and no-catch-up rules
     * described above against the last comment of each lane.
     *
     * @param minGapPx minimum horizontal gap between the previous
     *                 comment's tail and the right edge before a new
     *                 comment may enter the lane
     * @return the lane to use, or -1 if no lane qualifies yet and the
     * comment should wait
     */
    public int chooseLane(int textWidthPx, int containerWidthPx, int crossingSeconds,
                          int minGapPx, long nowMs) {
        double velocity = velocityPxPerMs(containerWidthPx, textWidthPx, crossingSeconds);
        if (velocity <= 0) {
            return -1;
        }
        for (int lane = 0; lane < maxRows; lane++) {
            if (laneStartMs[lane] == 0) {
                record(lane, textWidthPx, velocity, nowMs);
                return lane;
            }
            double prevVelocity = laneVelocityPxPerMs[lane];
            double prevTail = tailPositionPx(containerWidthPx, laneTextWidthPx[lane],
                    prevVelocity, laneStartMs[lane], nowMs);

            // Rule 1: the previous tail must have moved at least
            // minGapPx inside the right edge.
            if (prevTail > containerWidthPx - Math.max(0, minGapPx)) {
                continue;
            }
            // Rule 2: a faster new comment must not catch the previous
            // tail before that tail leaves the left edge. The gap only
            // shrinks while both are on screen, so check the new head
            // position at the moment the previous tail exits (tail = 0):
            // it must not have passed the left edge by then.
            if (velocity > prevVelocity && prevVelocity > 0) {
                double prevTailExitInMs = prevTail / prevVelocity;
                double newHeadAtPrevExit = containerWidthPx - velocity * prevTailExitInMs;
                if (newHeadAtPrevExit < 0) {
                    continue;
                }
            }
            record(lane, textWidthPx, velocity, nowMs);
            return lane;
        }
        return -1;
    }

    private void record(int lane, int textWidthPx, double velocityPxPerMs, long nowMs) {
        laneStartMs[lane] = Math.max(1, nowMs);
        laneTextWidthPx[lane] = textWidthPx;
        laneVelocityPxPerMs[lane] = velocityPxPerMs;
    }
}
