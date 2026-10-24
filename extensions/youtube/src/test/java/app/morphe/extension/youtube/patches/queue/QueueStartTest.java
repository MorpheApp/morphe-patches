/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3953
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.queue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Map;

import app.morphe.extension.youtube.innertube.queue.PlaylistQueueRenderer.NavigationEndpoint;
import app.morphe.extension.youtube.innertube.queue.PlaylistQueueRenderer.WatchEndpoint;

public class QueueStartTest {
    private final QueueStart start = new QueueStart(new PlaylistQueue("abcdefghijk", "Selected"), "lmnopqrstuv");

    @Test public void onlyRequestedVideoGetsMinimizedContextWithoutMutatingCaller() throws Exception {
        var requested = NavigationEndpoint.newBuilder().setWatchEndpoint(
                WatchEndpoint.newBuilder().setVideoId("abcdefghijk")).build();
        var unrelated = NavigationEndpoint.newBuilder().setWatchEndpoint(
                WatchEndpoint.newBuilder().setVideoId("ZYXWVUTSRQP")).build();
        var context = Map.of("native-context", new Object(), "start_watch_minimized", false);
        var minimized = start.minimizedContext(requested, context);
        assertEquals(true, minimized.get("start_watch_minimized"));
        assertSame(context.get("native-context"), minimized.get("native-context"));
        assertEquals(false, context.get("start_watch_minimized"));
        assertEquals(true, start.minimizedContext(requested, null).get("start_watch_minimized"));
        assertSame(context, start.minimizedContext(unrelated, context));
        assertSame(context, start.minimizedContext(NavigationEndpoint.getDefaultInstance(), context));
    }

    @Test public void unrelatedNavigationSupersedesRequestButPriorPlaybackDoesNot() {
        assertFalse(start.isSupersededBy(""));
        assertFalse(start.isSupersededBy("lmnopqrstuv"));
        assertFalse(start.isSupersededBy("abcdefghijk"));
        assertTrue(start.isSupersededBy("ZYXWVUTSRQP"));
    }

    @Test public void closedPlayerStartPreservesSelectedDuplicateAndUsesSavedTime() throws Exception {
        var queue = start.candidate().playLast("ZYXWVUTSRQP").playLast("abcdefghijk")
                .select(2).withRepeatMode(PlaylistQueue.REPEAT_ONE);
        var restore = new QueueStart(queue, "", 75_432);
        assertSame(queue, restore.candidate());
        assertEquals(2, restore.candidate().selectedIndex());
        assertEquals("https://www.youtube.com/watch?v=abcdefghijk&t=75s", restore.watchUrl());
        assertTrue(restore.restoresClosedPlayer());
        assertFalse(start.restoresClosedPlayer());
        assertEquals("https://www.youtube.com/watch?v=abcdefghijk", start.watchUrl());
        assertEquals("https://www.youtube.com/watch?v=abcdefghijk&t=0s",
                new QueueStart(queue, "", 0).watchUrl());
    }
}
