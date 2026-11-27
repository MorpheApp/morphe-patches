/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/issues/4863
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.playback;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Binder;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import app.morphe.extension.shared.innertube.queue.PlaylistQueueRenderer.NavigationEndpoint;
import app.morphe.extension.youtube.shared.PlayerType;

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
            assertEquals(Boolean.TRUE, context.get().get("ALLOW_RELOAD"));
            assertSame(request, context.get().get("app.morphe.continue_watching"));
            assertNull(PlaybackSessionPatch.prepareWatchDescriptor(new Object(), context.get()));
        }
    }

    @Test
    public void playerFromRemovedTaskDoesNotSuppressRestore() {
        var currentWindow = new Binder();
        var previousWindow = new Binder();

        assertEquals(PlaybackSessionPatch.StartupPlayer.TRANSITIONING, PlaybackSessionPatch.startupPlayer(
                PlayerType.WATCH_WHILE_MAXIMIZED, false, previousWindow, currentWindow));
        assertEquals(PlaybackSessionPatch.StartupPlayer.AVAILABLE, PlaybackSessionPatch.startupPlayer(
                PlayerType.WATCH_WHILE_MAXIMIZED, false, null, currentWindow));
        assertEquals(PlaybackSessionPatch.StartupPlayer.OPEN, PlaybackSessionPatch.startupPlayer(
                PlayerType.WATCH_WHILE_MINIMIZED, false, currentWindow, currentWindow));
        assertEquals(PlaybackSessionPatch.StartupPlayer.TRANSITIONING, PlaybackSessionPatch.startupPlayer(
                PlayerType.WATCH_WHILE_SLIDING_MINIMIZED_DISMISSED, false, currentWindow, currentWindow));
        assertEquals(PlaybackSessionPatch.StartupPlayer.AVAILABLE, PlaybackSessionPatch.startupPlayer(
                PlayerType.INLINE_MINIMAL, false, currentWindow, currentWindow));
        assertEquals(PlaybackSessionPatch.StartupPlayer.OPEN, PlaybackSessionPatch.startupPlayer(
                PlayerType.HIDDEN, true, null, currentWindow));
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
            saved.set(null, null);
            PlaybackSessionPatch.initializeActivity(firstLaunch, null);
            assertFalse(pending.getBoolean(null));

            var recorded = new PlaybackSessionSnapshot("account", "abcdefghijk", 10_437,
                    PlaybackSessionSnapshot.Kind.VIDEO, "", false, null, true);
            saved.set(null, PlaybackSessionSnapshot.fromJson(recorded.toJson()));
            lifecycle.onActivityDestroyed(firstLaunch);
            assertFalse(pending.getBoolean(null));

            PlaybackSessionPatch.initializeActivity(secondLaunch, null);
            assertTrue(pending.getBoolean(null));
            lifecycle.onActivityDestroyed(firstLaunch);
            assertTrue(pending.getBoolean(null));

            var request = PlaybackSessionPatch.beginRestore();
            assertEquals(recorded, request.snapshot());
            assertFalse(pending.getBoolean(null));
            var command = new AtomicReference<byte[]>();
            var context = new AtomicReference<Map<String, Object>>();
            PlaybackSessionPatch.dispatchSavedWatch((bytes, values) -> {
                command.set(bytes);
                context.set(values);
            }, request);
            var watch = NavigationEndpoint.parseFrom(command.get()).getWatchEndpoint();
            assertEquals(recorded.videoId(), watch.getVideoId());
            assertEquals(Boolean.TRUE, context.get().get("start_watch_minimized"));
            assertSame(request, context.get().get("app.morphe.continue_watching"));

            var descriptor = new TestDescriptor(watch.getVideoId());
            assertSame(descriptor, PlaybackSessionPatch.applyRestoredWatch(descriptor, request));
            assertTrue(descriptor.paused);
            assertEquals(10_437, descriptor.position);
            assertTrue(PlaybackSessionPatch.showRestoredMiniplayer(PlayerType.WATCH_WHILE_MINIMIZED));
            assertFalse(PlaybackSessionPatch.showRestoredMiniplayer(PlayerType.WATCH_WHILE_MAXIMIZED));
            assertPausedControls(secondLaunch);
            var captionSource = new TestCaptionSource(recorded.videoId());
            assertTrue(PlaybackSessionPatch.restoreCaptionPreference(true, new TestCaptionSource("lmnopqrstuv"), () -> true));
            assertTrue(PlaybackSessionPatch.restoreCaptionPreference(false, captionSource, () -> false));
            assertFalse(PlaybackSessionPatch.restoreCaptionPreference(false, captionSource, () -> false));

            PlaybackSessionPatch.applyRestoredWatch(descriptor, request);
            assertFalse(PlaybackSessionPatch.restoreCaptionPreference(true, captionSource, () -> true));

            for (Boolean captionsEnabled : new Boolean[]{false, null}) {
                var snapshot = new PlaybackSessionSnapshot(recorded.account(), recorded.videoId(),
                        recorded.positionMilliseconds(), recorded.kind(), "", false, null, captionsEnabled);
                var restoredSnapshot = PlaybackSessionSnapshot.fromJson(snapshot.toJson());
                assertEquals(captionsEnabled, restoredSnapshot.captionsEnabled());
                PlaybackSessionPatch.applyRestoredWatch(descriptor, new PlaybackSessionPatch.RestoreRequest(restoredSnapshot));
                assertFalse(PlaybackSessionPatch.restoreCaptionPreference(true, captionSource, () -> false));
            }

            PlaybackSessionPatch.initializeActivity(recreated, new Bundle());
            assertFalse(pending.getBoolean(null));
            assertFalse(PlaybackSessionPatch.showRestoredMiniplayer(PlayerType.WATCH_WHILE_MINIMIZED));
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

    private static void assertPausedControls(Context context) {
        var overlay = new TestBadge(context);
        var action = new TestAction(context);
        var badge = new TestBadge(context);
        var label = new TestLabel(context);
        var play = new ColorDrawable();
        RestoredMiniplayerControls.showPaused(overlay, action, play, "Phát", badge, label, "Tiếp tục xem");
        assertEquals(View.VISIBLE, overlay.getVisibility());
        assertEquals(View.VISIBLE, action.getVisibility());
        assertEquals(1f, action.getAlpha(), 0f);
        assertSame(play, action.getDrawable());
        assertEquals("Phát", action.getContentDescription());
        assertEquals(View.VISIBLE, badge.getVisibility());
        assertEquals("Tiếp tục xem", label.getText());
    }

    private static final class TestAction extends ImageView {
        private int visibility = View.GONE;
        private float alpha;
        private Drawable drawable;
        private CharSequence description = "Tạm dừng";

        TestAction(Context context) { super(context); }

        @Override public int getVisibility() { return visibility; }
        @Override public void setVisibility(int value) { visibility = value; }
        @Override public float getAlpha() { return alpha; }
        @Override public void setAlpha(float value) { alpha = value; }
        @Override public Drawable getDrawable() { return drawable; }
        @Override public void setImageDrawable(Drawable value) { drawable = value; }
        @Override public CharSequence getContentDescription() { return description; }
        @Override public void setContentDescription(CharSequence value) { description = value; }
    }

    private static final class TestBadge extends View {
        private int visibility = View.GONE;
        TestBadge(Context context) { super(context); }
        @Override public int getVisibility() { return visibility; }
        @Override public void setVisibility(int value) { visibility = value; }
    }

    private static final class TestLabel extends TextView {
        private CharSequence text = "";
        TestLabel(Context context) { super(context); }
        @Override public CharSequence getText() { return text; }
        @Override public void setText(CharSequence value, BufferType type) { text = value; }
    }

    private record TestCaptionSource(String videoId) implements PlaybackSessionPatch.CaptionSource {
        @Override public String patch_sessionCaptionVideoId() { return videoId; }
        @Override public boolean patch_sessionCaptionsEnabled() { return false; }
    }

    private static final class TestDescriptor implements PlaybackSessionPatch.Descriptor {
        private final String videoId;
        private boolean paused;
        private long position;
        TestDescriptor(String videoId) { this.videoId = videoId; }
        @Override public String patch_sessionVideoId() { return videoId; }
        @Override public void patch_startSessionPaused() { paused = true; }
        @Override public void patch_setSessionPosition(long value) { position = value; }
        @Override public PlaybackSessionPatch.Descriptor patch_newSessionQueue(List<String> videoIds) {
            throw new AssertionError("This session has no queue");
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
