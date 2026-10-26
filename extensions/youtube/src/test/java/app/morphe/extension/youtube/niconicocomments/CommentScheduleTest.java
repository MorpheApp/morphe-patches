package app.morphe.extension.youtube.niconicocomments;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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

    private static NiconicoComment comment(String id, long videoOffsetMs) {
        return new NiconicoComment(id, id, false, 0, videoOffsetMs);
    }
}
