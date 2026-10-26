package app.morphe.extension.youtube.niconicocomments;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/** Pure-Java comment scheduler shared by the Android view and JVM unit tests. */
final class CommentSchedule {
    static final long COMMENT_DURATION_MS = 7_000;

    private static final long LIVE_SPREAD_MIN_STEP_MS = 35;
    private static final long LIVE_SPREAD_MAX_STEP_MS = 160;
    private static final long LIVE_SPREAD_MIN_WINDOW_MS = 160;
    private static final long LIVE_SPREAD_MAX_WINDOW_MS = 1_000;
    private static final long LIVE_MAX_VISUAL_BACKLOG_MS = 750;

    private final List<ScheduledComment> comments = new ArrayList<>();
    private final Random random;

    private int[] shuffledLanes = new int[0];
    private int shuffledLaneCount;
    private int shuffledLaneIndex;
    private int lastLane = -1;
    private long nextLiveStartTimeMs = Long.MIN_VALUE;

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
            comments.add(new ScheduledComment(comment, startTimeMs, nextLane(laneCount)));
        }

        if (liveCount > 0) {
            nextLiveStartTimeMs = liveCursorMs;
        }
        recalculateLiveCursor(videoTimeMs, liveStepMs);
    }

    void removeExpired(long videoTimeMs) {
        Iterator<ScheduledComment> iterator = comments.iterator();
        while (iterator.hasNext()) {
            if (videoTimeMs - iterator.next().startTimeMs > COMMENT_DURATION_MS) {
                iterator.remove();
            }
        }
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
        for (ScheduledComment comment : comments) {
            comment.lane = nextLane(laneCount);
        }
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
        int lane;

        ScheduledComment(NiconicoComment comment, long startTimeMs, int lane) {
            this.comment = comment;
            this.startTimeMs = startTimeMs;
            this.lane = lane;
        }
    }
}
