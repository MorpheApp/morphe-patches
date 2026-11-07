/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4080
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 * 
 * Niconico-style live/replay comments implementation is adapted from
 * https://github.com/david419kr/niconico-yt-morphe-patches (GPLv3)
 * Feature design is based on david419kr/niconico-yt (MIT License)
 */

package app.morphe.extension.youtube.niconicocomments;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.ToDoubleFunction;

/** Pure-Java comment scheduler shared by the Android view and JVM unit tests. */
final class CommentSchedule {
    static final int MAX_LIVE_COMMENTS = 512;

    private static final long LIVE_SPREAD_MIN_STEP_MS = 35;
    private static final long LIVE_SPREAD_MAX_STEP_MS = 160;
    private static final long LIVE_SPREAD_MIN_WINDOW_MS = 160;
    private static final long LIVE_SPREAD_MAX_WINDOW_MS = 1_000;
    private static final long LIVE_MAX_VISUAL_BACKLOG_MS = 750;
    /**
     * A comment that fits in no lane can start up to this much later, so it still shows close to its message.
     */
    private static final long LANE_MAX_DELAY_MS = 1_000;
    /**
     * How much later each try starts.
     */
    private static final long LANE_DELAY_STEP_MS = 250;

    private final List<ScheduledComment> comments = new ArrayList<>();
    private final Random random;

    private int[] shuffledLanes = new int[0];
    private int shuffledLaneCount;
    private int shuffledLaneIndex;
    private int lastLane = -1;
    private long nextLiveStartTimeMs = Long.MIN_VALUE;
    private long commentDurationMs;
    private float viewWidth;
    private float minGap;
    private ToDoubleFunction<NiconicoComment> widthMeasurer;
    private boolean hideCrowdedComments;

    CommentSchedule() {
        this(new Random());
    }

    CommentSchedule(Random random) {
        this.random = random;
    }

    void addComments(
            List<NiconicoComment> incoming,
            long videoTimeMs,
            long liveSpreadWindowMs,
            int laneCount
    ) {
        removeExpired(videoTimeMs);

        int liveCount = 0;
        for (NiconicoComment comment : incoming) {
            if (comment.videoOffsetMs < 0) {
                liveCount++;
            }
        }

        long liveStepMs = calculateLiveSpreadStepMs(liveCount, liveSpreadWindowMs);
        long liveCursorMs = Math.max(videoTimeMs, nextLiveStartTimeMs);
        liveCursorMs = Math.min(liveCursorMs, videoTimeMs + LIVE_MAX_VISUAL_BACKLOG_MS);

        for (NiconicoComment comment : incoming) {
            long startTimeMs;
            if (comment.videoOffsetMs >= 0) {
                startTimeMs = comment.videoOffsetMs;
            } else {
                startTimeMs = liveCursorMs;
                liveCursorMs += liveStepMs;
            }
            ScheduledComment scheduled = place(comment, startTimeMs, laneCount, true);
            if (scheduled != null) {
                comments.add(scheduled);
            }
        }

        if (liveCount > 0) {
            nextLiveStartTimeMs = liveCursorMs;
            trimLiveOverflow(videoTimeMs);
        }
        recalculateLiveCursor(videoTimeMs, liveStepMs);
    }

    private void trimLiveOverflow(long videoTimeMs) {
        List<ScheduledComment> live = new ArrayList<>();
        for (ScheduledComment scheduled : comments) {
            if (scheduled.comment.videoOffsetMs < 0) live.add(scheduled);
        }
        if (live.size() <= MAX_LIVE_COMMENTS) return;

        // Keep recent visible comments, then the nearest upcoming live comments.
        live.sort(Comparator
                .comparingInt((ScheduledComment comment) -> comment.startTimeMs > videoTimeMs ? 1 : 0)
                .thenComparing((left, right) -> left.startTimeMs > videoTimeMs
                        ? Long.compare(left.startTimeMs, right.startTimeMs)
                        : Long.compare(right.startTimeMs, left.startTimeMs)));
        Set<ScheduledComment> dropped = new HashSet<>(live.subList(MAX_LIVE_COMMENTS, live.size()));
        // Replay lookahead is not disposable: its continuation has already advanced.
        comments.removeIf(dropped::contains);
    }

    void removeExpired(long videoTimeMs) {
        comments.removeIf(scheduledComment -> videoTimeMs - scheduledComment.startTimeMs > commentDurationMs);
    }

    /** How long a comment takes to cross the screen. */
    long getCommentDurationMs() {
        return commentDurationMs;
    }

    void setCommentDurationMs(long durationMs) {
        commentDurationMs = durationMs;
    }

    /**
     * Sets the sizes used to keep comments in the same lane from running into each other.
     * Until it is set, comments only take the next shuffled lane.
     */
    void setLayout(float viewWidth, float minGap, ToDoubleFunction<NiconicoComment> widthMeasurer) {
        this.viewWidth = viewWidth;
        this.minGap = minGap;
        this.widthMeasurer = widthMeasurer;
    }

    /**
     * @param hide True to not show a comment that fits in no lane, instead of overlapping another comment.
     */
    void setHideCrowdedComments(boolean hide) {
        hideCrowdedComments = hide;
    }

    void clear() {
        comments.clear();
        nextLiveStartTimeMs = Long.MIN_VALUE;
        shuffledLanes = new int[0];
        shuffledLaneCount = 0;
        shuffledLaneIndex = 0;
        lastLane = -1;
    }

    List<ScheduledComment> comments() {
        return comments;
    }

    void redistributeLanes(int laneCount) {
        resetLaneShuffle();
        List<ScheduledComment> scheduled = new ArrayList<>(comments);
        scheduled.sort(Comparator.comparingLong(comment -> comment.startTimeMs));
        comments.clear();
        for (ScheduledComment comment : scheduled) {
            // Scheduled comments keep their time and are never hidden here.
            comments.add(place(comment.comment, comment.startTimeMs, laneCount, false));
        }
    }

    /**
     * Puts a comment in a lane where it does not run into another comment.
     * The next shuffled lane is tried first, so the comments still spread out.
     *
     * @param newComment True for a new comment: it can start up to {@link #LANE_MAX_DELAY_MS} later,
     *                   and it is hidden when it fits in no lane and crowded comments are hidden.
     * @return The scheduled comment, or null if it is hidden. A comment that fits in no lane
     *         overlaps in the lane where the comment ahead has moved the farthest.
     */
    private ScheduledComment place(NiconicoComment comment, long startTimeMs, int requestedLaneCount, boolean newComment) {
        int laneCount = Math.max(1, requestedLaneCount);
        int preferredLane = nextLane(laneCount);
        float width = measure(comment);
        if (widthMeasurer == null || viewWidth <= 0 || commentDurationMs <= 0) {
            return new ScheduledComment(comment, startTimeMs, preferredLane, width);
        }

        long maxDelayMs = newComment ? LANE_MAX_DELAY_MS : 0;
        List<List<ScheduledComment>> lanes = getNearbyByLane(startTimeMs, startTimeMs + maxDelayMs, laneCount);
        int[] laneOrder = getLaneOrder(preferredLane, laneCount);
        for (long delayMs = 0; delayMs <= maxDelayMs; delayMs += LANE_DELAY_STEP_MS) {
            for (int lane : laneOrder) {
                if (isLaneFree(lanes.get(lane), startTimeMs + delayMs, width)) {
                    return new ScheduledComment(comment, startTimeMs + delayMs, lane, width);
                }
            }
        }

        if (newComment && hideCrowdedComments) {
            return null;
        }
        int leastCrowdedLane = preferredLane;
        long earliestLastStartMs = Long.MAX_VALUE;
        for (int lane : laneOrder) {
            long lastStartMs = Long.MIN_VALUE;
            for (ScheduledComment other : lanes.get(lane)) {
                if (other.startTimeMs <= startTimeMs) {
                    lastStartMs = Math.max(lastStartMs, other.startTimeMs);
                }
            }
            if (lastStartMs < earliestLastStartMs) {
                earliestLastStartMs = lastStartMs;
                leastCrowdedLane = lane;
            }
        }
        return new ScheduledComment(comment, startTimeMs, leastCrowdedLane, width);
    }

    /**
     * @return The preferred lane, then the other lanes in random order, so the free lanes are used evenly.
     */
    private int[] getLaneOrder(int preferredLane, int laneCount) {
        List<Integer> otherLanes = new ArrayList<>(laneCount);
        for (int lane = 0; lane < laneCount; lane++) {
            if (lane != preferredLane) {
                otherLanes.add(lane);
            }
        }
        Collections.shuffle(otherLanes, random);
        int[] laneOrder = new int[laneCount];
        laneOrder[0] = preferredLane;
        for (int index = 1; index < laneCount; index++) {
            laneOrder[index] = otherLanes.get(index - 1);
        }
        return laneOrder;
    }

    /**
     * @return For each lane, the comments that can meet a comment starting between the two times.
     */
    private List<List<ScheduledComment>> getNearbyByLane(long fromMs, long toMs, int laneCount) {
        List<List<ScheduledComment>> lanes = new ArrayList<>(laneCount);
        for (int lane = 0; lane < laneCount; lane++) {
            lanes.add(new ArrayList<>());
        }
        for (ScheduledComment other : comments) {
            if (other.startTimeMs > fromMs - commentDurationMs && other.startTimeMs < toMs + commentDurationMs) {
                lanes.get(Math.floorMod(other.lane, laneCount)).add(other);
            }
        }
        return lanes;
    }

    private boolean isLaneFree(List<ScheduledComment> laneComments, long startTimeMs, float width) {
        for (ScheduledComment other : laneComments) {
            boolean otherFirst = other.startTimeMs <= startTimeMs;
            if (!canFollow(
                    otherFirst ? other.startTimeMs : startTimeMs,
                    otherFirst ? other.width : width,
                    otherFirst ? startTimeMs : other.startTimeMs,
                    otherFirst ? width : other.width,
                    viewWidth,
                    commentDurationMs,
                    minGap
            )) {
                return false;
            }
        }
        return true;
    }

    /**
     * Comments cross the screen in the same time, so a wider comment moves faster.
     * A later comment can follow an earlier one in the same lane if the earlier one is fully on the
     * screen when the later one comes in, and the later one has not caught up when the earlier one leaves.
     * Both comments move at a constant speed, so checking these two moments is enough.
     */
    static boolean canFollow(
            long earlierStartMs,
            float earlierWidth,
            long laterStartMs,
            float laterWidth,
            float viewWidth,
            long durationMs,
            float minGap
    ) {
        long elapsedMs = laterStartMs - earlierStartMs;
        if (elapsedMs >= durationMs) {
            return true;
        }
        float earlierMoved = elapsedMs * (viewWidth + earlierWidth) / durationMs;
        if (earlierMoved < earlierWidth + minGap) {
            return false;
        }
        float laterLeftWhenEarlierLeaves = viewWidth
                - (durationMs - elapsedMs) * (viewWidth + laterWidth) / durationMs;
        return laterLeftWhenEarlierLeaves >= minGap;
    }

    private float measure(NiconicoComment comment) {
        return widthMeasurer == null ? 0 : (float) widthMeasurer.applyAsDouble(comment);
    }

    static long calculateLiveSpreadStepMs(int commentCount, long spreadWindowMs) {
        if (commentCount <= 1) {
            return 0;
        }
        long usableWindowMs = Math.min(
                LIVE_SPREAD_MAX_WINDOW_MS,
                Math.max(LIVE_SPREAD_MIN_WINDOW_MS, spreadWindowMs)
        );
        return Math.max(
                LIVE_SPREAD_MIN_STEP_MS,
                Math.min(LIVE_SPREAD_MAX_STEP_MS, usableWindowMs / commentCount)
        );
    }

    private int nextLane(int requestedLaneCount) {
        int laneCount = Math.max(1, requestedLaneCount);
        if (laneCount != shuffledLaneCount || shuffledLaneIndex >= shuffledLanes.length) {
            List<Integer> lanes = new ArrayList<>(laneCount);
            for (int lane = 0; lane < laneCount; lane++) {
                lanes.add(lane);
            }
            Collections.shuffle(lanes, random);
            if (laneCount > 1 && lanes.get(0) == lastLane) {
                Collections.swap(lanes, 0, 1);
            }
            shuffledLanes = new int[laneCount];
            for (int index = 0; index < laneCount; index++) {
                shuffledLanes[index] = lanes.get(index);
            }
            shuffledLaneCount = laneCount;
            shuffledLaneIndex = 0;
        }

        int lane = shuffledLanes[shuffledLaneIndex++];
        lastLane = lane;
        return lane;
    }

    private void resetLaneShuffle() {
        shuffledLanes = new int[0];
        shuffledLaneCount = 0;
        shuffledLaneIndex = 0;
        lastLane = -1;
    }

    private void recalculateLiveCursor(long videoTimeMs, long liveStepMs) {
        long latestPendingLiveMs = Long.MIN_VALUE;
        for (ScheduledComment scheduled : comments) {
            if (scheduled.comment.videoOffsetMs < 0 && scheduled.startTimeMs > videoTimeMs) {
                latestPendingLiveMs = Math.max(latestPendingLiveMs, scheduled.startTimeMs);
            }
        }
        nextLiveStartTimeMs = latestPendingLiveMs == Long.MIN_VALUE
                ? videoTimeMs
                : latestPendingLiveMs + Math.max(LIVE_SPREAD_MIN_STEP_MS, liveStepMs);
    }

    static final class ScheduledComment {
        final NiconicoComment comment;
        final long startTimeMs;
        final int lane;
        final float width;

        ScheduledComment(NiconicoComment comment, long startTimeMs, int lane, float width) {
            this.comment = comment;
            this.startTimeMs = startTimeMs;
            this.lane = lane;
            this.width = width;
        }
    }
}
