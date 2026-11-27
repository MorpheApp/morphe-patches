/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/issues/4863
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.playback;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.os.Bundle;

import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import app.morphe.extension.shared.innertube.queue.PlaylistQueueRenderer.NavigationEndpoint;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class PlaybackSessionStartupTest {
    @Test
    public void routerOpensSavedVideoWithMiniplayerContext() throws Exception {
        String videoId = "abcdefghijk";
        var playlist = new PlaybackSessionSnapshot("account", videoId, 2_505_438,
                PlaybackSessionSnapshot.Kind.VIDEO, "RD" + videoId, false, null);
        var localQueue = new PlaybackSessionSnapshot("account", videoId, 450,
                PlaybackSessionSnapshot.Kind.VIDEO, "RD" + videoId, false,
                new PlaybackSessionSnapshot.Queue(List.of(videoId, "lmnopqrstuv"), 0, null));
        var command = new AtomicReference<byte[]>();
        var context = new AtomicReference<Map<String, Object>>();
        for (var snapshot : List.of(playlist, localQueue)) {
            var request = new PlaybackSessionPatch.RestoreRequest(snapshot);
            PlaybackSessionPatch.dispatchSavedWatch((bytes, values) -> {
                command.set(bytes);
                context.set(values);
            }, request);

            var watch = NavigationEndpoint.parseFrom(command.get()).getWatchEndpoint();
            assertEquals(videoId, watch.getVideoId());
            assertEquals(snapshot.queue() == null ? snapshot.playlistId() : "", watch.getPlaylistId());
            assertEquals(Boolean.TRUE, context.get().get("start_watch_minimized"));
            assertSame(request, context.get().get("app.morphe.continue_watching"));
            assertNull(PlaybackSessionPatch.prepareWatchDescriptor(new Object(), context.get()));
        }
    }

    @Test
    public void launcherReopensAfterTaskRemovalInTheSameProcess() throws Exception {
        var saved = PlaybackSessionPatch.class.getDeclaredField("saved");
        var pending = PlaybackSessionPatch.class.getDeclaredField("checkingStartup");
        var callbacks = PlaybackSessionPatch.class.getDeclaredField("LIFECYCLE");
        var initialized = PlaybackSessionPatch.class.getDeclaredField("initialized");
        saved.setAccessible(true);
        pending.setAccessible(true);
        callbacks.setAccessible(true);
        initialized.setAccessible(true);
        var lifecycle = (Application.ActivityLifecycleCallbacks) callbacks.get(null);
        var previousSnapshot = saved.get(null);
        boolean wasInitialized = initialized.getBoolean(null);
        var firstLaunch = activity(Intent.ACTION_MAIN);
        var secondLaunch = activity(Intent.ACTION_MAIN);
        var recreated = activity(Intent.ACTION_MAIN);
        var deepLink = activity(Intent.ACTION_VIEW);
        try {
            initialized.setBoolean(null, true);
            saved.set(null, new PlaybackSessionSnapshot("account", "abcdefghijk", 10_000,
                    PlaybackSessionSnapshot.Kind.VIDEO, "", false, null));
            PlaybackSessionPatch.initializeActivity(firstLaunch, null);
            assertTrue(pending.getBoolean(null));
            lifecycle.onActivityDestroyed(firstLaunch);
            assertFalse(pending.getBoolean(null));

            PlaybackSessionPatch.initializeActivity(secondLaunch, null);
            assertTrue(pending.getBoolean(null));
            lifecycle.onActivityDestroyed(firstLaunch);
            assertTrue(pending.getBoolean(null));

            PlaybackSessionPatch.initializeActivity(recreated, new Bundle());
            assertFalse(pending.getBoolean(null));
            PlaybackSessionPatch.initializeActivity(deepLink, null);
            assertFalse(pending.getBoolean(null));
        } finally {
            lifecycle.onActivityDestroyed(firstLaunch);
            lifecycle.onActivityDestroyed(secondLaunch);
            lifecycle.onActivityDestroyed(recreated);
            lifecycle.onActivityDestroyed(deepLink);
            saved.set(null, previousSnapshot);
            initialized.setBoolean(null, wasInitialized);
        }
    }

    private static Activity activity(String action) {
        return new Activity() {
            @Override public Intent getIntent() {
                return new Intent() {
                    @Override public String getAction() {
                        return action;
                    }
                };
            }
        };
    }
}
