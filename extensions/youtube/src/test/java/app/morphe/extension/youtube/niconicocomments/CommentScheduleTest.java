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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

public class CommentScheduleTest {
    @Test
    public void neverDropsACommentThatHasAlreadyStarted() {
        CommentSchedule schedule = new CommentSchedule(new Random(1));
        List<NiconicoComment> comments = new ArrayList<>();
        comments.add(comment("active", 1_000));
        for (int index = 0; index < 1_000; index++) {
            comments.add(comment("future-" + index, 10_000 + index));
        }

        schedule.addComments(comments, 2_000, 0, 8);

        assertEquals(1_001, schedule.comments().size());
        assertTrue(schedule.comments().stream()
                .anyMatch(scheduled -> "active".equals(scheduled.comment.id)));
    }

    @Test
    public void spreadsLiveBatchAcrossTheNextPollingWindow() {
        CommentSchedule schedule = new CommentSchedule(new Random(2));
        List<NiconicoComment> comments = new ArrayList<>();
        for (int index = 0; index < 10; index++) {
            comments.add(comment("live-" + index, -1));
        }

        schedule.addComments(comments, 1_000, 250, 10);

        assertEquals(35, CommentSchedule.calculateLiveSpreadStepMs(10, 250));
        assertEquals(1_000, schedule.comments().get(0).startTimeMs);
        assertEquals(1_035, schedule.comments().get(1).startTimeMs);
        assertEquals(1_315, schedule.comments().get(9).startTimeMs);
    }

    @Test
    public void usesEveryLaneOnceInShuffledOrder() {
        CommentSchedule schedule = new CommentSchedule(new Random(3));
        List<NiconicoComment> comments = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            comments.add(comment("lane-" + index, -1));
        }

        schedule.addComments(comments, 0, 5_000, 8);

        Set<Integer> usedLanes = new HashSet<>();
        boolean differsFromSequentialOrder = false;
        for (int index = 0; index < schedule.comments().size(); index++) {
            int lane = schedule.comments().get(index).lane;
            usedLanes.add(lane);
            differsFromSequentialOrder |= lane != index;
        }
        assertEquals(8, usedLanes.size());
        assertTrue(differsFromSequentialOrder);

        schedule.redistributeLanes(2);
        Set<Integer> resizedLanes = new HashSet<>();
        for (CommentSchedule.ScheduledComment comment : schedule.comments()) {
            assertTrue(comment.lane >= 0 && comment.lane < 2);
            resizedLanes.add(comment.lane);
        }
        assertEquals(2, resizedLanes.size());
    }

    @Test
    public void boundsLiveCommentsWhenThePlaybackClockIsFrozen() {
        CommentSchedule schedule = new CommentSchedule(new Random(4));
        for (int round = 0; round < 200; round++) {
            List<NiconicoComment> incoming = new ArrayList<>();
            for (int index = 0; index < 50; index++) {
                incoming.add(comment(round + ":" + index, -1));
            }
            schedule.addComments(incoming, 10_000, 250, 12);
            assertTrue(schedule.comments().size() <= CommentSchedule.MAX_LIVE_COMMENTS);
        }
        assertEquals(CommentSchedule.MAX_LIVE_COMMENTS, schedule.comments().size());
    }

    @Test
    public void liveOverflowDoesNotDiscardFetchedReplayLookahead() {
        CommentSchedule schedule = new CommentSchedule(new Random(5));
        List<NiconicoComment> replay = new ArrayList<>();
        for (int index = 0; index < 1_001; index++) replay.add(comment("replay-" + index, 20_000 + index));
        schedule.addComments(replay, 10_000, 0, 12);
        List<NiconicoComment> live = new ArrayList<>();
        for (int index = 0; index < 2_000; index++) live.add(comment("live-" + index, -1));
        schedule.addComments(live, 10_000, 250, 12);

        assertEquals(1_001, schedule.comments().stream()
                .filter(scheduled -> scheduled.comment.videoOffsetMs >= 0).count());
        assertEquals(CommentSchedule.MAX_LIVE_COMMENTS, schedule.comments().stream()
                .filter(scheduled -> scheduled.comment.videoOffsetMs < 0).count());
    }

    @Test
    public void canFollowOnlyWhenCommentsNeverMeet() {
        // 1000 px wide screen, 7 s to cross it, 10 px gap.
        assertFalse(CommentSchedule.canFollow(0, 100, 0, 100, 1_000, 7_000, 10));
        // A narrow comment can follow once the one ahead has fully come in.
        assertFalse(CommentSchedule.canFollow(0, 100, 600, 100, 1_000, 7_000, 10));
        assertTrue(CommentSchedule.canFollow(0, 100, 700, 100, 1_000, 7_000, 10));
        // A wider comment needs more room, or it catches up before the narrow one leaves.
        assertFalse(CommentSchedule.canFollow(0, 100, 1_600, 300, 1_000, 7_000, 10));
        assertTrue(CommentSchedule.canFollow(0, 100, 1_700, 300, 1_000, 7_000, 10));
        assertTrue(CommentSchedule.canFollow(0, 100, 7_000, 300, 1_000, 7_000, 10));
    }

    @Test
    public void putsCommentsThatWouldRunIntoEachOtherInOtherLanes() {
        CommentSchedule schedule = scheduleWithLayout(6);
        List<NiconicoComment> comments = new ArrayList<>();
        for (int index = 0; index < 2; index++) comments.add(comment("same-time-" + index, 1_000));

        schedule.addComments(comments, 1_000, 0, 2);

        assertEquals(2, schedule.comments().size());
        assertNotEquals(schedule.comments().get(0).lane, schedule.comments().get(1).lane);
        assertEquals(1_000, schedule.comments().get(1).startTimeMs);
    }

    @Test
    public void commentsInTheSameLaneNeverRunIntoEachOther() {
        Random random = new Random(10);
        CommentSchedule schedule = new CommentSchedule(new Random(11));
        schedule.setCommentDurationMs(7_000);
        Map<String, Float> widths = new HashMap<>();
        List<NiconicoComment> comments = new ArrayList<>();
        for (int index = 0; index < 50; index++) {
            NiconicoComment comment = comment("comment-" + index, 1_000 + random.nextInt(2_000));
            widths.put(comment.id, 50f + random.nextInt(550));
            comments.add(comment);
        }
        schedule.setLayout(1_000, 10, comment -> widths.get(comment.id));
        schedule.setHideCrowdedComments(true);

        schedule.addComments(comments, 1_000, 0, 8);

        List<CommentSchedule.ScheduledComment> scheduled = schedule.comments();
        assertTrue(scheduled.size() > 8);
        for (CommentSchedule.ScheduledComment first : scheduled) {
            for (CommentSchedule.ScheduledComment second : scheduled) {
                if (first == second || first.lane != second.lane || first.startTimeMs > second.startTimeMs) {
                    continue;
                }
                assertTrue(CommentSchedule.canFollow(first.startTimeMs, first.width,
                        second.startTimeMs, second.width, 1_000, 7_000, 10));
            }
        }
    }

    @Test
    public void startsACommentALittleLaterWhenNoLaneIsFree() {
        CommentSchedule schedule = scheduleWithLayout(7);
        List<NiconicoComment> comments = new ArrayList<>();
        for (int index = 0; index < 2; index++) comments.add(comment("same-time-" + index, 1_000));

        schedule.addComments(comments, 1_000, 0, 1);

        assertEquals(2, schedule.comments().size());
        assertEquals(1_750, schedule.comments().get(1).startTimeMs);
    }

    @Test
    public void overlapsOrHidesACommentThatDoesNotFit() {
        List<NiconicoComment> comments = new ArrayList<>();
        for (int index = 0; index < 3; index++) comments.add(comment("same-time-" + index, 1_000));

        CommentSchedule overlapping = scheduleWithLayout(8);
        overlapping.addComments(comments, 1_000, 0, 1);
        assertEquals(3, overlapping.comments().size());
        assertEquals(1_000, overlapping.comments().get(2).startTimeMs);

        CommentSchedule hiding = scheduleWithLayout(8);
        hiding.setHideCrowdedComments(true);
        hiding.addComments(comments, 1_000, 0, 1);
        assertEquals(2, hiding.comments().size());
    }

    @Test
    public void placesCommentsAgainOnceTheSizesAreKnown() {
        CommentSchedule schedule = new CommentSchedule(new Random(12));
        schedule.setCommentDurationMs(7_000);
        List<NiconicoComment> comments = new ArrayList<>();
        for (int index = 0; index < 4; index++) comments.add(comment("same-time-" + index, 1_000));
        // Before the view is laid out, comments only take the shuffled lanes.
        schedule.addComments(comments, 1_000, 0, 1);

        schedule.setLayout(1_000, 10, comment -> 100);
        schedule.redistributeLanes(4);

        Set<Integer> lanes = new HashSet<>();
        for (CommentSchedule.ScheduledComment scheduled : schedule.comments()) {
            assertEquals(100, scheduled.width, 0);
            lanes.add(scheduled.lane);
        }
        assertEquals(4, lanes.size());
    }

    @Test
    public void keepsShownCommentsWhenTheLanesChange() {
        CommentSchedule schedule = scheduleWithLayout(9);
        schedule.setHideCrowdedComments(true);
        List<NiconicoComment> comments = new ArrayList<>();
        for (int index = 0; index < 4; index++) comments.add(comment("same-time-" + index, 1_000));
        schedule.addComments(comments, 1_000, 0, 4);

        schedule.redistributeLanes(1);

        assertEquals(4, schedule.comments().size());
    }

    /**
     * 1000 px wide screen, 7 s to cross it, 10 px gap, and every comment 100 px wide.
     */
    private static CommentSchedule scheduleWithLayout(int seed) {
        CommentSchedule schedule = new CommentSchedule(new Random(seed));
        schedule.setCommentDurationMs(7_000);
        schedule.setLayout(1_000, 10, comment -> 100);
        return schedule;
    }

    private static NiconicoComment comment(String id, long videoOffsetMs) {
        return new NiconicoComment(id, id, false, 0, videoOffsetMs);
    }
}
