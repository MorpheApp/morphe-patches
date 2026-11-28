/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5436
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.playback;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Binder;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import app.morphe.extension.shared.innertube.queue.PlaylistQueueRenderer.Item;
import app.morphe.extension.shared.innertube.queue.PlaylistQueueRenderer.NavigationEndpoint;
import app.morphe.extension.shared.innertube.queue.PlaylistQueueRenderer.Panel;
import app.morphe.extension.shared.innertube.queue.PlaylistQueueRenderer.Video;
import app.morphe.extension.shared.innertube.queue.PlaylistQueueRenderer.WatchEndpoint;
import app.morphe.extension.youtube.shared.PlayerType;

public class PlaybackSessionStartupTest {
    @Test
    public void routerOpensSavedVideoWithMiniplayerContext() throws Exception {
        String videoId = "abcdefghijk";
        var playlist = new PlaybackSessionSnapshot("account", videoId, 2_505_438,
                PlaybackSessionSnapshot.Kind.VIDEO, "RD" + videoId, false, null);
        var localQueue = new PlaybackSessionSnapshot("account", videoId, 450,
                PlaybackSessionSnapshot.Kind.VIDEO, "", false,
                new PlaybackSessionSnapshot.Queue(List.of(videoId, "lmnopqrstuv"), 0, null));
        var command = new AtomicReference<byte[]>();
        var context = new AtomicReference<Map<String, Object>>();
        var personalPlaylist = new PlaybackSessionSnapshot("account", videoId, 450,
                PlaybackSessionSnapshot.Kind.VIDEO, "PL1234567890", false,
                new PlaybackSessionSnapshot.Queue(List.of(videoId), 0, null, 42));
        var partialMix = new PlaybackSessionSnapshot("account", videoId, 450,
                PlaybackSessionSnapshot.Kind.VIDEO, "RD" + videoId, false,
                new PlaybackSessionSnapshot.Queue(List.of(videoId), 0, null));
        for (var snapshot : List.of(playlist, localQueue, personalPlaylist, partialMix)) {
            var request = new PlaybackSessionPatch.RestoreRequest(snapshot);
            PlaybackSessionPatch.dispatchSavedWatch((bytes, values) -> {
                command.set(bytes);
                context.set(values);
            }, request);

            var watch = NavigationEndpoint.parseFrom(command.get()).getWatchEndpoint();
            assertEquals(videoId, watch.getVideoId());
            assertEquals(snapshot.playlistId(), watch.getPlaylistId());
            if (snapshot == personalPlaylist) {
                assertTrue(watch.hasIndex());
                assertEquals(42, watch.getIndex());
            } else {
                assertFalse(watch.hasIndex());
            }
            assertEquals(Boolean.TRUE, context.get().get("start_watch_minimized"));
            assertEquals(Boolean.TRUE, context.get().get("ALLOW_RELOAD"));
            assertSame(request, context.get().get("app.morphe.continue_watching"));
            assertNull(PlaybackSessionPatch.prepareWatchDescriptor(new Object(), context.get()));
        }
    }

    @Test
    public void restoresOriginalPlaylistAndMixWithMultipleCapturedItems() throws Exception {
        String current = "abcdefghijk";
        String next = "lmnopqrstuv";
        String last = "wxyz1234567";
        for (String playlistId : List.of("PL1234567890", "RD" + current)) {
            // The server index is relative to the full source, not this partially loaded panel.
            var panel = Panel.newBuilder().setPlaylistId(playlistId).setSelectedIndex(42)
                    .addItems(playlistRow(playlistId, current, 40))
                    .addItems(playlistRow(playlistId, next, 41))
                    .addItems(playlistRow(playlistId, current, 42))
                    .addItems(Item.getDefaultInstance())
                    .addItems(playlistRow(playlistId, last, 44))
                    .addItems(playlistRow(playlistId, next, 45))
                    .addItems(playlistRow(playlistId, last, 46))
                    .build();
            var queue = PlaybackSessionSnapshot.Queue.fromPanel(panel, current);
            var saved = new PlaybackSessionSnapshot("account", current, 12_345,
                    PlaybackSessionSnapshot.Kind.VIDEO, playlistId, false, queue);
            var request = new PlaybackSessionPatch.RestoreRequest(PlaybackSessionSnapshot.fromJson(saved.toJson()));
            var descriptor = new TestDescriptor(current);
            try {
                assertSame(descriptor, PlaybackSessionPatch.applyRestoredWatch(descriptor, request));
                var command = new AtomicReference<byte[]>();
                PlaybackSessionPatch.dispatchSavedWatch((bytes, context) -> command.set(bytes), request);
                var watch = NavigationEndpoint.parseFrom(command.get()).getWatchEndpoint();
                assertEquals(current, watch.getVideoId());
                assertEquals(playlistId, watch.getPlaylistId());
                assertTrue(watch.hasIndex());
                assertEquals(42, watch.getIndex());
                assertNull(descriptor.queue);
                assertEquals(12_345, descriptor.position);
                assertTrue(descriptor.paused);
                var restored = request.snapshot();
                assertEquals(queue, restored.queueWhilePanelUnavailable("", true));
                assertEquals(queue, restored.queueWhilePanelUnavailable(playlistId, false));
                assertNull(restored.queueWhilePanelUnavailable("", false));
                assertNull(restored.queueWhilePanelUnavailable("PL-other", true));
            } finally {
                PlaybackSessionPatch.onRegularWatchCommand(new TestDescriptor(next));
            }
        }
    }

    @Test
    public void restoresUserQueueOrderEvenWithAnOldMixId() throws Exception {
        String current = "abcdefghijk";
        String next = "lmnopqrstuv";
        String earlier = "wxyz1234567";
        var queue = new PlaybackSessionSnapshot.Queue(List.of(earlier, current, next, current), 1, 2);
        var saved = new PlaybackSessionSnapshot("account", current, 12_345,
                PlaybackSessionSnapshot.Kind.VIDEO, "RD" + earlier, false, queue);
        var request = new PlaybackSessionPatch.RestoreRequest(PlaybackSessionSnapshot.fromJson(saved.toJson()));
        var command = new AtomicReference<byte[]>();
        PlaybackSessionPatch.dispatchSavedWatch((bytes, context) -> command.set(bytes), request);

        var watch = NavigationEndpoint.parseFrom(command.get()).getWatchEndpoint();
        assertEquals(current, watch.getVideoId());
        assertFalse(watch.hasPlaylistId());
        assertEquals(Integer.valueOf(2), request.snapshot().queueForRestore().nativeRepeatMode());

        var descriptor = new TestDescriptor(current);
        try {
            assertSame(descriptor, PlaybackSessionPatch.applyRestoredWatch(descriptor, request));
            assertEquals(List.of(current, next, current), descriptor.queue);
            assertEquals(12_345, descriptor.position);
            assertTrue(descriptor.paused);
        } finally {
            PlaybackSessionPatch.onRegularWatchCommand(new TestDescriptor(next));
        }
    }

    @Test
    public void opensLivePausedWithoutApplyingTheGenericSavedPosition() throws Exception {
        String current = "abcdefghijk";
        var saved = new PlaybackSessionSnapshot("account", current, 12_345,
                PlaybackSessionSnapshot.Kind.LIVE, "", false, null);
        var descriptor = new TestDescriptor(current);
        try {
            var request = new PlaybackSessionPatch.RestoreRequest(saved);
            var command = new AtomicReference<byte[]>();
            PlaybackSessionPatch.dispatchSavedWatch((bytes, context) -> {
                command.set(bytes);
                assertEquals(Boolean.TRUE, context.get("start_watch_minimized"));
            }, request);
            assertEquals(current, NavigationEndpoint.parseFrom(command.get()).getWatchEndpoint().getVideoId());
            assertSame(descriptor, PlaybackSessionPatch.applyRestoredWatch(descriptor, request));
            assertTrue(descriptor.paused);
            assertEquals(-1, descriptor.position);
        } finally {
            PlaybackSessionPatch.onRegularWatchCommand(new TestDescriptor("lmnopqrstuv"));
        }
    }

    private static Item playlistRow(String playlistId, String videoId, int index) {
        return Item.newBuilder().setVideo(Video.newBuilder().setVideoId(videoId)
                .setNavigationEndpoint(NavigationEndpoint.newBuilder()
                        .setWatchEndpoint(WatchEndpoint.newBuilder()
                                .setVideoId(videoId).setPlaylistId(playlistId).setIndex(index)))).build();
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
    public void detachedRestoreLabelHasLayoutParametersBeforeUpdatingText() {
        var text = new AtomicReference<CharSequence>();
        var label = new TextView(activity(Intent.ACTION_MAIN)) {
            private ViewGroup.LayoutParams params;

            @Override public ViewGroup.LayoutParams getLayoutParams() { return params; }
            @Override public void setLayoutParams(ViewGroup.LayoutParams value) { params = value; }

            @Override public void setText(CharSequence value, TextView.BufferType type) {
                assertTrue(params != null);
                text.set(value);
            }
        };

        RestoredMiniplayerLabel.measureLabel(label, "Continue watching", View.LAYOUT_DIRECTION_LTR, 160);
        assertEquals("Continue watching", text.get());
        var params = label.getLayoutParams();
        RestoredMiniplayerLabel.measureLabel(label, "Tiếp tục xem", View.LAYOUT_DIRECTION_LTR, 120);
        assertEquals("Tiếp tục xem", text.get());
        assertSame(params, label.getLayoutParams());
    }

    @Test
    public void restoredLabelFollowsTheMiniplayerFrameInWindowCoordinates() {
        var player = new Rect();
        player.left = 341;
        player.top = 1160;
        player.right = 705;
        player.bottom = 1364;
        var position = new int[4];
        var badge = new ColorDrawable() {
            @Override public void setBounds(int left, int top, int right, int bottom) {
                position[0] = left;
                position[1] = top;
                position[2] = right;
                position[3] = bottom;
            }
        };

        RestoredMiniplayerLabel.placeLabel(badge, player, 155, 32, 12, false);
        assertArrayEquals(new int[]{353, 1320, 508, 1352}, position);
        RestoredMiniplayerLabel.placeLabel(badge, player, 155, 32, 12, true);
        assertArrayEquals(new int[]{538, 1320, 693, 1352}, position);
    }

    @Test
    public void launcherReopensAfterTaskRemovalInTheSameProcess() throws Exception {
        var saved = PlaybackSessionPatch.class.getDeclaredField("saved");
        var pending = PlaybackSessionPatch.class.getDeclaredField("checkingStartup");
        var callbacks = PlaybackSessionPatch.class.getDeclaredField("LIFECYCLE");
        var initialized = PlaybackSessionPatch.class.getDeclaredField("initialized");
        var restoredWatch = PlaybackSessionPatch.class.getDeclaredField("restoredWatch");
        saved.setAccessible(true);
        pending.setAccessible(true);
        callbacks.setAccessible(true);
        initialized.setAccessible(true);
        restoredWatch.setAccessible(true);
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

            var watchState = (PlaybackSessionPatch.RestoredWatch) restoredWatch.get(null);
            assertTrue(PlaybackSessionPatch.restoredWatchAwaitingPlayback());
            assertEquals(0, watchState.onPlayerTypeChanged(PlayerType.WATCH_WHILE_MINIMIZED, 0));
            long firstDeadline = watchState.onPlayerTypeChanged(PlayerType.WATCH_WHILE_MAXIMIZED, 100);
            assertTrue(watchState.canPlayOnExpand(PlayerType.WATCH_WHILE_MAXIMIZED, firstDeadline, 2_850));
            assertFalse(watchState.canPlayOnExpand(PlayerType.WATCH_WHILE_MAXIMIZED, firstDeadline, firstDeadline));
            watchState.onPlayerTypeChanged(PlayerType.WATCH_WHILE_MINIMIZED, firstDeadline + 1);
            assertTrue(PlaybackSessionPatch.showRestoredMiniplayer(PlayerType.WATCH_WHILE_MINIMIZED));

            long secondDeadline = watchState.onPlayerTypeChanged(PlayerType.WATCH_WHILE_MAXIMIZED, firstDeadline + 100);
            watchState.onPlayRequested();
            assertFalse(watchState.canPlayOnExpand(PlayerType.WATCH_WHILE_MAXIMIZED, secondDeadline, firstDeadline + 101));
            watchState.startedPlaying = true;
            watchState.onPlayerTypeChanged(PlayerType.WATCH_WHILE_MINIMIZED, firstDeadline + 200);
            assertTrue(PlaybackSessionPatch.showRestoredMiniplayer(PlayerType.WATCH_WHILE_MINIMIZED));
            assertFalse(PlaybackSessionPatch.restoredWatchAwaitingPlayback());
            assertPlayingControls(secondLaunch);

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

            var previousVideo = (PlaybackSessionPatch.RestoredWatch) restoredWatch.get(null);
            previousVideo.startedPlaying = true;
            PlaybackSessionPatch.onRegularWatchCommand(new TestDescriptor(recorded.videoId()));
            assertSame(previousVideo, restoredWatch.get(null));

            // Opening another video clears the restore badge and paused override.
            // Returning to Home still shows that video's native playback button.
            PlaybackSessionPatch.onRegularWatchCommand(new TestDescriptor("lmnopqrstuv"));
            assertFalse(PlaybackSessionPatch.showMiniplayerPlaybackControls(PlayerType.WATCH_WHILE_MAXIMIZED));
            assertFalse(PlaybackSessionPatch.showRestoredMiniplayer(PlayerType.WATCH_WHILE_MINIMIZED));
            assertFalse(PlaybackSessionPatch.restoredWatchAwaitingPlayback());
            assertTrue(PlaybackSessionPatch.showMiniplayerPlaybackControls(PlayerType.WATCH_WHILE_MINIMIZED));
            assertPlayingControls(secondLaunch);

            PlaybackSessionPatch.initializeActivity(recreated, new Bundle());
            assertFalse(pending.getBoolean(null));
            assertFalse(PlaybackSessionPatch.showRestoredMiniplayer(PlayerType.WATCH_WHILE_MINIMIZED));
            assertFalse(PlaybackSessionPatch.showMiniplayerPlaybackControls(PlayerType.WATCH_WHILE_MINIMIZED));
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
        var overlay = new TestOverlay(context);
        var action = new TestAction(context);
        var play = new ColorDrawable();
        RestoredMiniplayerControls.showControls(overlay, action, play, "Phát");
        assertEquals(View.VISIBLE, overlay.getVisibility());
        assertEquals(View.VISIBLE, action.getVisibility());
        assertEquals(1f, action.getAlpha(), 0f);
        assertSame(play, action.getDrawable());
        assertEquals("Phát", action.getContentDescription());
    }

    private static void assertPlayingControls(Context context) {
        var overlay = new TestOverlay(context);
        var action = new TestAction(context);
        var pause = new ColorDrawable();
        action.setImageDrawable(pause);
        RestoredMiniplayerControls.showControls(overlay, action, null, null);
        assertEquals(View.VISIBLE, overlay.getVisibility());
        assertEquals(View.VISIBLE, action.getVisibility());
        assertSame(pause, action.getDrawable());
        assertEquals("Tạm dừng", action.getContentDescription());
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

    private static final class TestOverlay extends View {
        private int visibility = View.GONE;
        TestOverlay(Context context) { super(context); }
        @Override public int getVisibility() { return visibility; }
        @Override public void setVisibility(int value) { visibility = value; }
    }

    private record TestCaptionSource(String videoId) implements PlaybackSessionPatch.CaptionSource {
        @Override public String patch_sessionCaptionVideoId() { return videoId; }
        @Override public boolean patch_sessionCaptionsEnabled() { return false; }
    }

    private static final class TestDescriptor implements PlaybackSessionPatch.Descriptor {
        private final String videoId;
        private boolean paused;
        private long position = -1;
        private List<String> queue;
        TestDescriptor(String videoId) { this.videoId = videoId; }
        @Override public String patch_sessionVideoId() { return videoId; }
        @Override public void patch_startSessionPaused() { paused = true; }
        @Override public void patch_setSessionPosition(long value) { position = value; }
        @Override public PlaybackSessionPatch.Descriptor patch_newSessionQueue(List<String> videoIds) {
            queue = List.copyOf(videoIds);
            return this;
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
