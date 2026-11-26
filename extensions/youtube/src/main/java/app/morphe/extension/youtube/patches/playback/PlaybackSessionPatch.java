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
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.AtomicFile;

import androidx.annotation.Nullable;

import com.google.protobuf.MessageLite;

import org.json.JSONException;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.innertube.queue.PlaylistQueueRenderer.NavigationEndpoint;
import app.morphe.extension.shared.innertube.queue.PlaylistQueueRenderer.Panel;
import app.morphe.extension.shared.innertube.queue.PlaylistQueueRenderer.WatchEndpoint;
import app.morphe.extension.youtube.patches.MiniplayerPatch.MiniplayerType;
import app.morphe.extension.youtube.patches.OpenShortsInRegularPlayerPatch.ShortsPlayerType;
import app.morphe.extension.youtube.patches.OpenVideosFullscreenHookPatch;
import app.morphe.extension.youtube.patches.VideoInformation;
import app.morphe.extension.youtube.patches.ask.LiveStatusRequest;
import app.morphe.extension.youtube.patches.queue.NativePlaylistQueuePatch;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.PlayerType;
import app.morphe.extension.youtube.shared.ShortsPlayerState;

/** Saves and restores a single active player, independently of watch history. */
@SuppressWarnings("unused")
public final class PlaybackSessionPatch {
    private static final String RESTORE_CONTEXT = "app.morphe.continue_watching";
    private static final long SAVE_INTERVAL_MS = 5_000;
    private static final long STARTUP_TIMEOUT_MS = 10_000;
    private static final long STARTUP_POLL_MS = 250;

    public interface WatchStarter {
        void patch_startSessionWatch(byte[] command, Map<?, ?> context);
    }

    public interface Descriptor {
        String patch_sessionVideoId();
        void patch_startSessionPaused();
        void patch_setSessionPosition(long positionMilliseconds);
        Descriptor patch_newSessionQueue(List<String> videoIds);
    }

    public interface AccountProvider {
        @Nullable Account patch_sessionAccount();
    }

    public interface PlaylistReader {
        String patch_sessionPlaylistVideoId();
        @Nullable Object patch_sessionPlaylistPanel();
    }

    public record Account(String id, boolean incognito) {}

    private static final Application.ActivityLifecycleCallbacks LIFECYCLE = new Application.ActivityLifecycleCallbacks() {
        @Override public void onActivityCreated(Activity activity, Bundle state) {}
        @Override public void onActivityStarted(Activity activity) {}
        @Override public void onActivityResumed(Activity activity) {
            if (activity == owner.get() && checkingStartup) {
                Utils.runOnMainThread(() -> restoreIfReady(activity));
            }
        }
        @Override public void onActivityPaused(Activity activity) {
            if (activity == owner.get()) save(true);
        }
        @Override public void onActivityStopped(Activity activity) {
            if (activity == owner.get()) save(true);
        }
        @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
        @Override public void onActivityDestroyed(Activity activity) {
            if (activity == owner.get()) cancelStartup();
        }
    };

    private static WeakReference<Activity> owner = new WeakReference<>(null);
    private static volatile WeakReference<AccountProvider> accountProvider = new WeakReference<>(null);
    private static volatile WeakReference<WatchStarter> watchStarter = new WeakReference<>(null);
    private static volatile WeakReference<MediaSession> mediaSession = new WeakReference<>(null);
    private static volatile WeakReference<PlaylistReader> playlist = new WeakReference<>(null);
    @Nullable private static AtomicFile storage;
    @Nullable private static PlaybackSessionSnapshot saved;
    @Nullable private static PlaybackSessionSnapshot restoring;
    @Nullable private static String dismissedVideo;
    @Nullable private static volatile String restoringMinimizedVideo;
    @Nullable private static volatile String restoredShort;
    @Nullable private static String restoredLive;
    private static boolean initialized;
    private static boolean checkingStartup;
    private static boolean restoreWasMinimized;
    private static long startupDeadline;
    private static long lastSaveTime;

    public static boolean isPatchIncluded() {
        return false; // Modified during patching.
    }

    private static boolean enabled() {
        return isPatchIncluded() && Settings.MINIPLAYER_RESTORE_SESSION.get();
    }

    public static void initialize(Activity activity, @Nullable Bundle state) {
        try {
            if (!enabled()) return;
            owner = new WeakReference<>(activity);
            if (initialized) return;
            initialized = true;
            storage = new AtomicFile(new File(activity.getNoBackupFilesDir(), "morphe_playback_session.json"));
            saved = read();
            Intent intent = activity.getIntent();
            checkingStartup = saved != null && state == null && intent != null
                    && Intent.ACTION_MAIN.equals(intent.getAction()) && intent.getData() == null;
            startupDeadline = SystemClock.elapsedRealtime() + STARTUP_TIMEOUT_MS;
            activity.getApplication().registerActivityLifecycleCallbacks(LIFECYCLE);
            PlayerType.getOnChange().addObserver(PlaybackSessionPatch::playerTypeChanged);
        } catch (Exception ex) {
            Logger.printException(() -> "Playback session initialization failed", ex);
        }
    }

    public static void attachWatchStarter(WatchStarter starter) {
        watchStarter = new WeakReference<>(starter);
    }

    public static void attachAccount(AccountProvider provider) {
        if (enabled()) accountProvider = new WeakReference<>(provider);
    }

    public static void attachMediaSession(MediaSession session) {
        if (enabled()) mediaSession = new WeakReference<>(session);
    }

    public static void attachPlaylist(PlaylistReader reader) {
        if (enabled()) playlist = new WeakReference<>(reader);
    }

    @Nullable
    private static String currentAccount() {
        AccountProvider provider = accountProvider.get();
        Account account = provider == null ? null : provider.patch_sessionAccount();
        return account == null || account.incognito() || account.id() == null || account.id().isEmpty()
                ? null : account.id();
    }

    @Nullable
    private static PlaybackSessionSnapshot read() {
        try {
            return PlaybackSessionSnapshot.fromJson(new String(storage.readFully(), StandardCharsets.UTF_8));
        } catch (FileNotFoundException ex) {
            return null;
        } catch (IOException | JSONException | IllegalArgumentException ex) {
            Logger.printException(() -> "Could not read the playback session", ex);
            storage.delete();
            return null;
        }
    }

    private static void update(@Nullable PlaybackSessionSnapshot snapshot) {
        if (storage == null || Objects.equals(saved, snapshot)) return;
        if (snapshot == null) {
            storage.delete();
            saved = null;
            return;
        }
        FileOutputStream stream = null;
        try {
            byte[] bytes = snapshot.toJson().getBytes(StandardCharsets.UTF_8);
            stream = storage.startWrite();
            stream.write(bytes);
            storage.finishWrite(stream);
            saved = snapshot;
        } catch (IOException | JSONException ex) {
            if (stream != null) storage.failWrite(stream);
            Logger.printException(() -> "Could not save the playback session", ex);
        }
    }

    public static void videoTimeChanged(long ignoredTime) {
        if (enabled()) Utils.runOnMainThreadNowOrLater(() -> save(false));
    }

    private static void save(boolean flush) {
        try {
            if (!enabled() || !initialized || checkingStartup || restoring != null) return;
            final long now = SystemClock.elapsedRealtime();
            if (!flush && now - lastSaveTime < SAVE_INTERVAL_MS) return;
            String videoId = VideoInformation.getVideoId();
            if (!VideoInformation.isVideoId(videoId) || videoId.equals(dismissedVideo)) return;
            PlayerType type = PlayerType.getCurrent();
            final boolean shorts = ShortsPlayerState.isOpen() && !type.isMaximizedOrFullscreen();
            if (!shorts && (type.isNoneOrHidden() || type == PlayerType.INLINE_MINIMAL)) return;
            String account = currentAccount();
            final long position = VideoInformation.getVideoTime();
            if (account == null || position < 0) return;
            PlaybackSessionSnapshot.Kind kind;
            final boolean sameSavedVideo = saved != null && account.equals(saved.account())
                    && videoId.equals(saved.videoId());
            final boolean knownLive = videoId.equals(VideoInformation.getPlayerResponseVideoId())
                    && VideoInformation.isLiveStream();
            if (knownLive || sameSavedVideo && saved.kind() == PlaybackSessionSnapshot.Kind.LIVE) {
                kind = PlaybackSessionSnapshot.Kind.LIVE;
            } else if (shorts || VideoInformation.lastVideoIdIsShort()
                    || sameSavedVideo && saved.kind() == PlaybackSessionSnapshot.Kind.SHORT) {
                kind = PlaybackSessionSnapshot.Kind.SHORT;
            } else {
                kind = PlaybackSessionSnapshot.Kind.VIDEO;
            }
            lastSaveTime = now;
            if (kind == PlaybackSessionSnapshot.Kind.SHORT && Settings.DISABLE_SHORTS_RESUMING_ON_STARTUP.get()) {
                update(null);
                return;
            }
            String playlistId = kind == PlaybackSessionSnapshot.Kind.SHORT ? "" : VideoInformation.getPlaylistId();
            final boolean usesKeptQueue = !playlistId.isEmpty() && Settings.QUEUE_RESTORE.get()
                    && playlistId.equals(Settings.QUEUE_PLAYLIST_ID.get());
            var queue = usesKeptQueue || kind == PlaybackSessionSnapshot.Kind.SHORT
                    ? null : captureQueue(videoId, playlistId);
            update(new PlaybackSessionSnapshot(account, videoId, position, kind,
                    usesKeptQueue ? "" : playlistId, usesKeptQueue, queue));
        } catch (Exception ex) {
            Logger.printException(() -> "Could not capture playback session", ex);
        }
    }

    @Nullable
    private static PlaybackSessionSnapshot.Queue captureQueue(String videoId, String playlistId) {
        var nativeQueue = NativePlaylistQueuePatch.captureSessionQueue(videoId);
        if (nativeQueue != null) return nativeQueue;
        PlaylistReader reader = playlist.get();
        if (reader == null || !videoId.equals(reader.patch_sessionPlaylistVideoId())
                || !(reader.patch_sessionPlaylistPanel() instanceof MessageLite message)) return null;
        try {
            Panel panel = Panel.parseFrom(message.toByteArray());
            if (!playlistId.isEmpty() && !panel.getPlaylistId().isEmpty()
                    && !playlistId.equals(panel.getPlaylistId())) return null;
            List<String> videoIds = new ArrayList<>();
            int selectedIndex = -1;
            for (var item : panel.getItemsList()) {
                if (!item.hasVideo()) continue;
                var video = item.getVideo();
                if (video.hasUnplayableText() || !VideoInformation.isVideoId(video.getVideoId())) continue;
                if (video.getSelected()) {
                    if (selectedIndex >= 0 || !videoId.equals(video.getVideoId())) return null;
                    selectedIndex = videoIds.size();
                }
                videoIds.add(video.getVideoId());
            }
            return selectedIndex < 0 ? null : new PlaybackSessionSnapshot.Queue(videoIds, selectedIndex, null);
        } catch (Exception ex) {
            Logger.printDebug(() -> "Could not capture the active playlist", ex);
            return null;
        }
    }

    /** Called before native code tears down a miniplayer explicitly closed by the user. */
    public static void onMiniplayerDismissed() {
        if (!enabled()) return;
        try {
            dismissedVideo = VideoInformation.getVideoId();
            cancelStartup();
            update(null);
        } catch (Exception ex) {
            Logger.printException(() -> "Could not clear the playback session", ex);
        }
    }

    public static boolean suppressNativeResume() {
        if (!enabled()) return false;
        try {
            String account = currentAccount();
            return (checkingStartup || restoring != null) && saved != null && account != null
                    && account.equals(saved.account()) && canRestore(saved);
        } catch (Exception ex) {
            Logger.printException(() -> "Could not check native playback resume", ex);
            return false;
        }
    }

    public static boolean handlesLiveRestore() {
        return enabled() && restoredLive != null;
    }

    public static boolean isShortAwaitingPlay() {
        return restoredShort != null && enabled() && ShortsPlayerState.isOpen()
                && restoredShort.equals(VideoInformation.getVideoId());
    }

    public static void onPlayerStatus(@Nullable Enum<?> status) {
        if (restoredShort != null && status != null && "VIDEO_PLAYING".equals(status.name())
                && restoredShort.equals(VideoInformation.getVideoId())) {
            restoredShort = null;
        }
        if (restoringMinimizedVideo != null && status != null && "VIDEO_PLAYING".equals(status.name())
                && restoringMinimizedVideo.equals(VideoInformation.getVideoId())) {
            restoringMinimizedVideo = null;
        }
    }

    private static boolean canRestore(PlaybackSessionSnapshot snapshot) {
        if (snapshot.kind() == PlaybackSessionSnapshot.Kind.SHORT
                && Settings.DISABLE_SHORTS_RESUMING_ON_STARTUP.get()) return false;
        return usesShortsPlayer(snapshot) || Settings.MINIPLAYER_TYPE.get() != MiniplayerType.DISABLED;
    }

    private static boolean usesShortsPlayer(PlaybackSessionSnapshot snapshot) {
        return snapshot.kind() == PlaybackSessionSnapshot.Kind.SHORT
                && Settings.SHORTS_PLAYER_TYPE.get() == ShortsPlayerType.SHORTS_PLAYER;
    }

    private static void restoreIfReady(Activity activity) {
        try {
            if (!checkingStartup || activity != owner.get()) return;
            var snapshot = saved;
            if (!enabled() || snapshot == null || activity.isFinishing() || activity.isDestroyed()
                    || SystemClock.elapsedRealtime() >= startupDeadline) {
                cancelStartup();
                return;
            }
            String account = currentAccount();
            WatchStarter starter = watchStarter.get();
            if (account == null || starter == null || !activity.hasWindowFocus()) {
                Utils.runOnMainThreadDelayed(() -> restoreIfReady(activity), STARTUP_POLL_MS);
                return;
            }
            checkingStartup = false;
            if (!account.equals(snapshot.account())) return;
            if (!canRestore(snapshot)) {
                if (snapshot.kind() == PlaybackSessionSnapshot.Kind.SHORT
                        && Settings.DISABLE_SHORTS_RESUMING_ON_STARTUP.get()) update(null);
                return;
            }
            if (!PlayerType.getCurrent().isNoneOrHidden() && PlayerType.getCurrent() != PlayerType.INLINE_MINIMAL
                    || ShortsPlayerState.isOpen()) return;
            restoring = snapshot;
            if (snapshot.kind() == PlaybackSessionSnapshot.Kind.LIVE) {
                Utils.runOnBackgroundThread(() -> {
                    Boolean live = LiveStatusRequest.fetchFresh(snapshot.videoId());
                    Utils.runOnMainThread(() -> {
                        if (restoring != snapshot) return;
                        if (Boolean.TRUE.equals(live)) start(activity, starter, snapshot);
                        else {
                            cancelStartup();
                            if (Boolean.FALSE.equals(live)) update(null);
                        }
                    });
                });
            } else {
                start(activity, starter, snapshot);
            }
        } catch (Exception ex) {
            cancelStartup();
            Logger.printException(() -> "Could not restore playback session", ex);
        }
    }

    private static void start(Activity activity, WatchStarter starter, PlaybackSessionSnapshot snapshot) {
        try {
            if (!enabled() || activity != owner.get() || !activity.hasWindowFocus()
                    || SystemClock.elapsedRealtime() >= startupDeadline
                    || !snapshot.account().equals(currentAccount())) {
                cancelStartup();
                return;
            }
            Utils.runOnMainThreadDelayed(() -> {
                if (restoring == snapshot) cancelStartup();
            }, STARTUP_TIMEOUT_MS);
            if (usesShortsPlayer(snapshot)) {
                var uri = Uri.parse("https://www.youtube.com/shorts/" + snapshot.videoId());
                activity.startActivity(new Intent(Intent.ACTION_VIEW, uri).setPackage(activity.getPackageName()));
                return;
            }
            var watch = WatchEndpoint.newBuilder().setVideoId(snapshot.videoId());
            if (snapshot.queue() == null) {
                String playlistId = snapshot.usesKeptQueue() && Settings.QUEUE_RESTORE.get()
                        ? Settings.QUEUE_PLAYLIST_ID.get() : snapshot.playlistId();
                if (!playlistId.isEmpty()) watch.setPlaylistId(playlistId);
            }
            var command = NavigationEndpoint.newBuilder().setWatchEndpoint(watch).build();
            Map<String, Object> context = new HashMap<>();
            context.put(RESTORE_CONTEXT, snapshot);
            context.put("start_watch_minimized", true);
            OpenVideosFullscreenHookPatch.keepVideoMinimized(snapshot.videoId());
            starter.patch_startSessionWatch(command.toByteArray(), context);
        } catch (Exception ex) {
            cancelStartup();
            Logger.printException(() -> "Could not open the saved video", ex);
        }
    }

    @Nullable
    public static Object prepareWatchDescriptor(Object original, @Nullable Map<?, ?> context) {
        if (!enabled()) return original;
        try {
            if (context == null || !(context.get(RESTORE_CONTEXT) instanceof PlaybackSessionSnapshot snapshot)) {
                cancelStartup();
                dismissedVideo = null;
                return original;
            }
            if (!(original instanceof Descriptor descriptor) || snapshot != restoring
                    || !snapshot.videoId().equals(descriptor.patch_sessionVideoId())) {
                cancelStartup();
                return null;
            }
            descriptor.patch_startSessionPaused();
            if (snapshot.queue() != null
                    && !NativePlaylistQueuePatch.prepareSessionQueue(snapshot.queue(), snapshot.positionMilliseconds())) {
                descriptor = descriptor.patch_newSessionQueue(snapshot.queue().upcoming());
                descriptor.patch_startSessionPaused();
            }
            if (snapshot.kind() != PlaybackSessionSnapshot.Kind.LIVE
                    || Settings.REMEMBER_LIVE_STREAM_POSITION.get()) {
                descriptor.patch_setSessionPosition(snapshot.positionMilliseconds());
            }
            if (snapshot.kind() == PlaybackSessionSnapshot.Kind.LIVE) restoredLive = snapshot.videoId();
            restoringMinimizedVideo = snapshot.videoId();
            restoreWasMinimized = false;
            restoring = null;
            return descriptor;
        } catch (Exception ex) {
            cancelStartup();
            Logger.printException(() -> "Could not prepare paused watch session", ex);
            return null;
        }
    }

    public static void prepareShortsDescriptor(Object original, @Nullable Map<?, ?> ignoredContext) {
        if (!enabled()) return;
        try {
            var snapshot = restoring;
            if (snapshot != null && usesShortsPlayer(snapshot) && original instanceof Descriptor descriptor
                    && snapshot.videoId().equals(descriptor.patch_sessionVideoId())) {
                descriptor.patch_startSessionPaused();
                restoredShort = snapshot.videoId();
                restoring = null;
            } else {
                cancelStartup();
                dismissedVideo = null;
            }
        } catch (Exception ex) {
            cancelStartup();
            Logger.printException(() -> "Could not prepare paused Shorts session", ex);
        }
    }

    private static void playerTypeChanged(PlayerType type) {
        Utils.runOnMainThreadNowOrLater(() -> {
            String videoId = restoringMinimizedVideo;
            if (videoId == null) return;
            if (type == PlayerType.WATCH_WHILE_MINIMIZED) {
                restoreWasMinimized = true;
            } else if (restoreWasMinimized && type.isMaximizedOrFullscreen()) {
                restoreWasMinimized = false;
                playExpandedVideo(videoId, SystemClock.elapsedRealtime() + 2_000);
            }
        });
    }

    private static void playExpandedVideo(String videoId, long deadline) {
        try {
            if (!enabled() || !videoId.equals(restoringMinimizedVideo)) return;
            if (!PlayerType.getCurrent().isMaximizedOrFullscreen() || SystemClock.elapsedRealtime() >= deadline) {
                restoringMinimizedVideo = null;
                return;
            }
            MediaSession session = mediaSession.get();
            if (videoId.equals(VideoInformation.getVideoId()) && session != null && session.isActive()) {
                var controller = session.getController();
                PlaybackState state = controller.getPlaybackState();
                if (state != null && state.getState() == PlaybackState.STATE_PAUSED) {
                    restoringMinimizedVideo = null;
                    controller.getTransportControls().play();
                    return;
                }
            }
            Utils.runOnMainThreadDelayed(() -> playExpandedVideo(videoId, deadline), STARTUP_POLL_MS);
        } catch (Exception ex) {
            restoringMinimizedVideo = null;
            Logger.printException(() -> "Could not play the expanded session", ex);
        }
    }

    private static void cancelStartup() {
        checkingStartup = false;
        restoring = null;
        restoredLive = null;
        restoredShort = null;
        if (restoringMinimizedVideo != null) {
            restoringMinimizedVideo = null;
            OpenVideosFullscreenHookPatch.keepVideoMinimized(null);
        }
    }

    private PlaybackSessionPatch() {}
}
