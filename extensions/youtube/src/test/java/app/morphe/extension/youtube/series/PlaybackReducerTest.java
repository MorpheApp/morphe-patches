/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4415
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.series;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class PlaybackReducerTest {
    private static final String A = "aaaaaaaaaaa", B = "bbbbbbbbbbb", C = "ccccccccccc";
    private static final long DURATION = 600_000;

    private final List<String> moves = new ArrayList<>();
    private final List<PlaybackReducer.Snapshot> snapshots = new ArrayList<>();
    private final PlaybackReducer reducer = new PlaybackReducer(
            snapshots::add, (previous, video) -> moves.add(previous + ">" + video), () -> 92, () -> 30);
    private long now = 10_000;

    private void sample(String video, long position) {
        reducer.sample(video, video, position, DURATION, false, now, now);
        now += 1000;
    }

    private void play(String video) {
        sample(video, 0);
        sample(video, 1000);
        sample(video, 2000);
    }

    @Test
    public void movingOnReportsThePreviousVideoOnce() {
        play(A);
        play(B);
        sample(B, 3000);
        assertEquals(List.of(A + ">" + B), moves);
    }

    @Test
    public void openingWithoutPlaybackIsNotReported() {
        sample(A, 0);
        sample(A, 0);
        play(B);
        assertEquals(List.of(), moves);
    }

    @Test
    public void skippedVideoWithoutPlaybackIsNotTheNewPrevious() {
        play(A);
        reducer.newVideo(now);
        sample(B, 0);
        play(C);
        assertEquals(List.of(A + ">" + C), moves);
    }

    @Test
    public void reloadingTheSameVideoIsNotReported() {
        play(A);
        reducer.newVideo(now);
        play(A);
        play(B);
        assertEquals(List.of(A + ">" + B), moves);
    }

    @Test
    public void movingBackIsReported() {
        play(A);
        play(B);
        play(A);
        assertEquals(List.of(A + ">" + B, B + ">" + A), moves);
    }

    @Test
    public void clearForgetsThePreviousVideo() {
        play(A);
        reducer.clear();
        play(B);
        assertEquals(List.of(), moves);
    }

    @Test
    public void excludedPlaybackForgetsThePreviousVideo() {
        play(A);
        reducer.sample(C, C, 0, DURATION, true, now, now);
        play(B);
        assertEquals(List.of(), moves);
    }

    @Test
    public void thresholdCompletionIsUnchanged() {
        play(A);
        now += 3000;
        sample(A, DURATION - 20_000);
        PlaybackReducer.Snapshot last = snapshots.get(snapshots.size() - 1);
        assertEquals(A, last.videoId());
        assertTrue(last.completed());
        assertEquals(DURATION - 20_000, last.positionMs());
        assertEquals(List.of(), moves);
    }
}
