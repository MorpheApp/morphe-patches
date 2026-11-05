/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4415
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.series;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;
import java.util.Map;

public class ResumePlannerTest {
    private static final String A = "aaaaaaaaaaa", B = "bbbbbbbbbbb", C = "ccccccccccc", D = "ddddddddddd";

    private static TrackerModels.Episode episode(int ordinal, String video, boolean available) {
        return new TrackerModels.Episode(ordinal, video, video, 600_000, available);
    }

    private static TrackerModels.Series series(boolean reverse, TrackerModels.Episode... episodes) {
        return new TrackerModels.Series("PLseries", "Series", "epoch", 1, "", -1, 0, "", "complete", "",
                0, 0, List.of(episodes), Map.of(), false, 0, reverse, false, true, "");
    }

    @Test
    public void nextEpisode() {
        TrackerModels.Series s = series(false, episode(1, A, true), episode(2, B, true), episode(3, C, true));
        assertTrue(ResumePlanner.isNext(s, A, B));
        assertTrue(ResumePlanner.isNext(s, B, C));
    }

    @Test
    public void earlierOrLaterEpisodeIsNotNext() {
        TrackerModels.Series s = series(false, episode(1, A, true), episode(2, B, true), episode(3, C, true));
        assertFalse(ResumePlanner.isNext(s, B, A));
        assertFalse(ResumePlanner.isNext(s, A, C));
        assertFalse(ResumePlanner.isNext(s, C, A));
    }

    @Test
    public void unavailableEpisodesAreSkipped() {
        TrackerModels.Series s = series(false, episode(1, A, true), episode(2, B, false), episode(3, C, true));
        assertTrue(ResumePlanner.isNext(s, A, C));
        assertFalse(ResumePlanner.isNext(s, A, B));
    }

    @Test
    public void reverseOrder() {
        TrackerModels.Series s = series(true, episode(1, A, true), episode(2, B, true), episode(3, C, true));
        assertTrue(ResumePlanner.isNext(s, C, B));
        assertFalse(ResumePlanner.isNext(s, A, B));
    }

    @Test
    public void anyOccurrenceOfARepeatedEpisode() {
        TrackerModels.Series s = series(false,
                episode(1, A, true), episode(2, B, true), episode(3, C, true), episode(4, A, true), episode(5, D, true));
        assertTrue(ResumePlanner.isNext(s, A, B));
        assertTrue(ResumePlanner.isNext(s, A, D));
        assertFalse(ResumePlanner.isNext(s, A, C));
    }
}
