/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3953
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.queue;

import static app.morphe.extension.shared.StringRef.str;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.text.Layout;
import android.util.Base64;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityManager;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.google.protobuf.MessageLite;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

import app.morphe.extension.shared.ByteTrieSearch;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.youtube.innertube.IconOuterClass.Icon;
import app.morphe.extension.youtube.innertube.IconOuterClass.YTIconType;
import app.morphe.extension.youtube.innertube.NextResponseOuterClass.SingleColumnWatchNextResults;
import app.morphe.extension.youtube.innertube.queue.PlaylistQueueRenderer;
import app.morphe.extension.youtube.patches.AddToQueuePatch.QueueType;
import app.morphe.extension.youtube.patches.LoadVideoPatch;
import app.morphe.extension.youtube.patches.OpenVideosFullscreenHookPatch;
import app.morphe.extension.youtube.patches.VideoInformation;
import app.morphe.extension.youtube.patches.originaltitles.RestoreOriginalTitlesPatch;
import app.morphe.extension.youtube.patches.utils.FeedVideoCardsPatch;
import app.morphe.extension.youtube.patches.utils.FlyoutUtils;
import app.morphe.extension.youtube.patches.utils.requests.ChannelIdRequest;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.PlayerType;
import app.morphe.extension.youtube.shared.ShortsPlayerState;

@SuppressWarnings("unused")
public final class NativePlaylistQueuePatch {
    private static final String SAVE_VIDEO_IDS_ARGUMENT = "app.morphe.native_playlist_queue.save_video_ids";
    private static final String ENDPOINT_TAG_KEY = "com.google.android.libraries.youtube.innertube.endpoint.tag";
    private static final ByteTrieSearch CARD_COMPONENTS = new ByteTrieSearch(
            ByteTrieSearch.convertStringsToBytes("video_lockup_with_attachment.eml"));
    private static final Map<String, VideoMetadata> metadataCache =
            Collections.synchronizedMap(Utils.createSizeRestrictedMap(1000));

    // Accessed only on the main thread; shared lookups may outlive the queue.
    private static final Set<String> pendingMetadata = new HashSet<>();
    private static final Map<String, QueueRatingActions> ratingActions =
            Collections.synchronizedMap(Utils.createSizeRestrictedMap(50));

    /** Implemented on the native playlist controller during patching. */
    public interface PlaylistController {
        String patch_getVideoId();
        String patch_getPlaylistId();
        Object patch_getModel();
        Object patch_getPanel();
        int patch_findQueueRow(Object target);
        void patch_markQueueRows();
        boolean patch_isAttached();
        Object patch_getNavigator();
        Object patch_updatePanel(byte[] panel);
        void patch_restorePanel(Object expectedModel, Object originalModel);
        void patch_showQueueFeedback(byte[] command);
    }

    /** Native playlist controls, which hold the playlist's navigator. */
    public interface NavigatorOwner {
        Object patch_getNavigator();
    }

    /** Native control owner; weak registration allows eligibility refresh after local edits. */
    public interface PlayerControls {
        Object patch_getNavigator();
        void patch_refreshNavigation();
    }

    /** Native notification owner; its service navigator can load without a watch screen. */
    public interface MediaControls {
        ServiceNavigator patch_getQueueNavigator();
    }

    /** Native adapter publishes capability changes to both notification paths. */
    public interface NotificationControls {
        MediaControls patch_getQueueMediaControls();
        void patch_refreshQueueNotification();
    }

    public interface ServiceNavigator {
        /** No active player is bound while the service is starting or resetting. */
        @Nullable
        String patch_getVideoId();
        NativePlayback patch_getQueuePlayback();
        void patch_navigateQueue(boolean next);
        boolean patch_canNavigateQueue(boolean next);
    }

    /** Bridge to YouTube's mutable playback queue, independent of the watch view. */
    public interface NativePlayback {
        boolean patch_isLocalQueue();
        boolean patch_attachQueue(byte[] panel, byte[] currentWatchCommand);
        void patch_updateSnapshot(byte[] panel);
        int patch_getSelectedIndex();
        int patch_getRepeatMode();
        void patch_restoreQueueRepeat(int repeatMode);
        boolean patch_clearQueue();
    }

    public interface RepeatHeader {
        Object patch_getNavigator();
        Object patch_getPanel();
        void patch_enableRepeat();
        void patch_setShuffleAvailable(boolean available);
    }

    /** Native playlist title and its currently bound panel. */
    public interface PlaylistTitle {
        Object patch_getPanel();
        TextView patch_getTitle();
    }

    /** Read-only identity of a model which passed YouTube's stale-video checks. */
    public interface PlaylistModel {
        String patch_getPlaylistId();
    }

    /** Native listener bridge retains the bound endpoint before logging decorates it. */
    public interface RowClick {
        Object patch_getQueueEndpoint();
    }

    /** Refreshes the native swipe widget's existing foreground background cache. */
    public interface SwipeBackground {
        void patch_refreshQueueSwipeBackground();
    }

    /** Nonserialized ownership bit on native rows from an accepted local panel. */
    public interface QueuePanel {
        boolean patch_isQueueOwned();
    }

    public interface QueueRow {
        boolean patch_isQueueOwned();
    }

    /** Native adapter bridge; its rows are the exact accepted protobuf objects. */
    public interface ReorderItems {
        int patch_getSize();
        Object patch_getQueueRow(int index);
    }

    private static final class DragSession {
        final Object holder;
        final ReorderItems items;
        final Object model;
        final PlaylistQueue snapshot;
        final Object row;
        final int from;
        boolean ended;

        DragSession(Object holder, ReorderItems items, Object row, int from) {
            this.holder = holder;
            this.items = items;
            this.row = row;
            this.from = from;
            model = publishedModel;
            snapshot = queue;
        }
    }

    private static DragSession dragSession;
    private static WeakReference<PlaylistController> controllerRef = new WeakReference<>(null);
    private static PlaylistQueue queue;
    private static WeakReference<NativePlayback> nativePlaybackRef = new WeakReference<>(null);
    private static Object originalModel;
    private static Object publishedModel;
    private record TitleWidth(int original, int applied) {}
    private static final Map<TextView, TitleWidth> originalTitleWidths = new WeakHashMap<>();

    // The running video keeps its original player-response identity when only its
    // navigation is replaced. Retain this exact source/owner through Clear and Undo;
    // do not rewrite shared VideoInformation or claim another playlist/player.
    private record ConvertedPlaylist(String videoId, String playlistId, WeakReference<NativePlayback> playback) {}
    private static volatile ConvertedPlaylist convertedPlaylist;

    // Background playback can confirm selection before the watch screen catches up.
    private static boolean selectionNeedsPublishing;
    private static boolean publishing;
    private static boolean observingPlayer;

    // SharedPreferences holds listeners weakly. Retain ours for the installed patch's lifetime.
    private static final SharedPreferences.OnSharedPreferenceChangeListener QUEUE_SETTING_LISTENER =
            (preferences, key) -> {
                if (Settings.QUEUE_TYPE.key.equals(key)) {
                    // Let the setting cache update before checking the chosen provider.
                    Utils.runOnMainThread(() -> {
                        try {
                            if (isNativeQueueDisabled()) releaseNativeQueue();
                        } catch (Exception ex) {
                            Logger.printException(() -> "Queue type setting change failure", ex);
                        }
                    });
                }
            };

    private static final QueueShortsSession shortsSession = new QueueShortsSession();

    // A return response can precede Shorts detachment. Remember its controller
    // weakly, without transferring the old panel's ownership/restoration baseline.
    private static WeakReference<PlaylistController> shortsControllerRef = new WeakReference<>(null);
    private record RowSelection(PlaylistQueue.Entry entry) {}
    private static RowSelection pendingSelection;

    // A menu click can open the first video before a watch controller exists.
    // Keep this intent separate from an attached, usable native queue.
    private static QueueStart pendingQueueStart;
    private static int pendingQueueStartInlinePlaybackBlockId;
    private static final class ClosedPlayback {
        final long positionMilliseconds;
        @Nullable PlaylistController feedbackOwner;

        ClosedPlayback(long positionMilliseconds, PlaylistController feedbackOwner) {
            this.positionMilliseconds = positionMilliseconds;

            // Keep the dispatcher alive through teardown, then release it on show.
            // The Undo expiry also bounds a close that never finishes. This owner
            // is never used to restore playback.
            this.feedbackOwner = feedbackOwner;
        }
    }

    private record PendingUndo(String token, PlaylistQueue before, WeakReference<NativePlayback> playback,
                               ClosedPlayback closedPlayback) {}
    private static PendingUndo pendingUndo;

    // Reload opens a standalone video, which may have no playlist model. Its
    // dispatched intent and completed native playback transition identify the owner.
    private static final class PendingReload {
        final PlaylistQueue before;
        WeakReference<PlaylistController> controller = new WeakReference<>(null);
        boolean opening;
        boolean watchDispatched;

        PendingReload(PlaylistQueue before) {
            this.before = before;
        }
    }
    private static PendingReload pendingReload;
    private static final int UNDO_DURATION_MILLISECONDS = 5_000;
    private static final int ACCESSIBLE_UNDO_DURATION_MILLISECONDS = 20_000;
    private static final Set<RepeatHeader> repeatHeaders = Collections.newSetFromMap(new WeakHashMap<>());
    private static final Set<PlayerControls> playerControls = Collections.newSetFromMap(new WeakHashMap<>());
    private static final Set<NotificationControls> notificationControls = Collections.newSetFromMap(new WeakHashMap<>());

    // Native navigation can be rejected (for example while signed out). A failed
    // click must not authorize a much later, unrelated open of the same video.
    private static final int NAVIGATION_TIMEOUT_MILLISECONDS = 10_000;

    private NativePlaylistQueuePatch() {}

    public static boolean isPatchIncluded() {
        return false; // Modified during patching.
    }

    /** Read-only response hook; captures commands even when the action bar is hidden. */
    public static void onWatchNextResponse(MessageLite response) {
        if (response == null) return;
        try {
            var results = SingleColumnWatchNextResults.parseFrom(response.toByteArray());
            for (var section : results.getPrimaryResults().getSecondaryResults().getSecondaryContentsList()) {
                if (!section.hasSlimVideoMetadataSectionRenderer()) continue;
                var metadata = section.getSlimVideoMetadataSectionRenderer();
                for (var content : metadata.getTertiaryContentsList()) {
                    QueueRatingActions actions = QueueRatingActions.fromElement(
                            content.getElementRenderer().getNewElement());
                    if (actions == null || (!metadata.getVideoId().isEmpty()
                            && !metadata.getVideoId().equals(actions.videoId()))) continue;
                    ratingActions.put(actions.videoId(), actions);

                    // Finish the native response parse before updating any panel.
                    Utils.runOnMainThread(() -> {
                        if (actions.videoId().equals(VideoInformation.getVideoId())) refreshOwnedPanel(actions.videoId());
                    });
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "onWatchNextResponse failure", ex);
        }
    }

    /** Read-only shared element hook; preserves bytes and every other patch's changes. */
    public static byte[] onElementLoaded(byte[] bytes) {
        if (bytes == null || !CARD_COMPONENTS.matches(bytes)) return bytes;
        try {
            VideoMetadata metadata = VideoMetadata.fromElement(bytes);
            if (metadata != null) {
                metadataCache.compute(metadata.videoId(), (videoId, previous) ->
                        previous == null ? metadata : previous.withCapture(metadata));
            }
        } catch (Exception ex) {
            Logger.printException(() -> "onElementLoaded failure", ex);
        }

        return bytes;
    }

    private static void rememberChannel(String videoId, String title, String channelName) {
        metadataCache.compute(videoId, (id, metadata) -> {
            if (metadata == null) return new VideoMetadata(id, title, channelName, "", "", false);
            if (metadata.title().equals(id) && !title.equals(id)) {
                metadata = new VideoMetadata(id, title, metadata.channelName(), metadata.videoInfo(), metadata.duration(), metadata.fullByline());
            }

            return metadata.withChannelName(channelName);
        });
    }

    /** Injection point, after a native model acceptance or completed video transition. */
    public static void onControllerUpdated(PlaylistController controller) {
        onControllerUpdated(controller, false);
    }

    /** A completed native descriptor transition can leave the playlist model null. */
    public static void onPlaybackTransition(PlaylistController controller) {
        onControllerUpdated(controller, true);
    }

    private static void onControllerUpdated(PlaylistController controller, boolean playbackTransition) {
        Utils.runOnMainThreadNowOrLater(() -> {
            try {
                PendingReload reload = pendingReload;
                if (playbackTransition && reload != null && reload.opening && !hasPlaylist(controller)
                        && reload.before.currentEntry().videoId().equals(controller.patch_getVideoId())) {
                    reload.controller = new WeakReference<>(controller);
                }
                updateController(controller);
            } catch (Exception ex) {
                Logger.printException(() -> "onControllerUpdated failure", ex);
            }
        });
    }

    private static void updateController(PlaylistController controller) {
        if (!observingPlayer) {
            Setting.preferences.preferences.registerOnSharedPreferenceChangeListener(QUEUE_SETTING_LISTENER);
            observingPlayer = true;
            PlayerType.getOnChange().addObserver(type -> {
                onPlaybackContextChanged();
                return kotlin.Unit.INSTANCE;
            });
            ShortsPlayerState.getOnChange().addObserver(open -> {
                onPlaybackContextChanged();
                return kotlin.Unit.INSTANCE;
            });
        }

        // A queued callback may arrive after our synchronous update has returned. Never
        // treat our own accepted model as a new native baseline or publish it again.
        if (publishing) return;
        if (isNativeQueueDisabled()) {
            releaseNativeQueue();
            if (queue == null) controllerRef = new WeakReference<>(controller);
            return;
        }

        syncNativeSelection(VideoInformation.getVideoId());

        // A regular return response can arrive before the shared video ID changes.
        // Only native controller callbacks update this candidate; lifecycle rechecks
        // must not overwrite it with the old owner while waiting for that ID.
        if (queue != null && isQueueSuspended() && !controller.patch_getVideoId().isEmpty()) {
            shortsControllerRef = new WeakReference<>(controller);
        }

        if (updateShortsSession(controller)) return;

        // The service can advance while a retained watch screen still describes the
        // previous video. Only the active-ID hooks can commit background selection.
        if (queue != null && !controller.patch_getVideoId().equals(VideoInformation.getVideoId())) return;
        if (waitingForNativeSelection(VideoInformation.getVideoId())) return;
        if (publishedModel != null && controllerRef.get() == controller
                && controller.patch_getModel() == publishedModel
                && controller.patch_getVideoId().equals(queue.currentEntry().videoId())) {
            if (selectionNeedsPublishing && readyController() == controller) {
                publish(controller, queue, false);
            }

            refreshRepeatHeaders();
            return;
        }

        PlaylistController previous = controllerRef.get();
        if (queue != null && previous != controller) {
            if (previous != null && previous.patch_isAttached()
                    && previous.patch_getVideoId().equals(VideoInformation.getVideoId())
                    && (!hasPlaylist(controller) || (!controller.patch_isAttached()
                    && !hasForeignPlaybackPlaylist()))) return;

            // Recreating a watch screen must not discard an owned background
            // session. Its new controller still needs a fresh native baseline.
            if (hasPlaylist(controller) || (!serviceOwnsQueue()
                    && controller.patch_getVideoId().equals(queue.currentEntry().videoId()))) releasePanel();
            else {
                if (controller.patch_getModel() != publishedModel) originalModel = controller.patch_getModel();
                publishedModel = null;
            }
        }

        controllerRef = new WeakReference<>(controller);
        if (queue == null) {
            // The native acceptance hook runs immediately after the model store,
            // before its subscriptions and rendering finish. Never reenter it.
            if (pendingQueueStart != null) Utils.runOnMainThread(NativePlaylistQueuePatch::tryStartQueue);
            if (pendingReload != null) Utils.runOnMainThread(NativePlaylistQueuePatch::showPendingReloadFeedback);
            return;
        }

        // A row click authorizes only that exact entry. Wait for the authoritative
        // video-ID hook before committing selection; prefetched responses cannot do it.
        if (!hasPlaylist(controller) && pendingSelection != null
                && controller.patch_getVideoId().equals(pendingSelection.entry().videoId())) return;
        if (hasPlaylist(controller)) {
            Object navigator = controller.patch_getNavigator();
            queue = null;
            pendingUndo = null;
            shortsSession.reset();
            shortsControllerRef.clear();
            dragSession = null;
            pendingSelection = null;
            originalModel = null;
            publishedModel = null;
            selectionNeedsPublishing = false;
            refreshPlayerControls(navigator);
            QueueFullscreenActions.refresh();
            return;
        }

        dragSession = null;
        String videoId = controller.patch_getVideoId();
        if (!videoId.equals(queue.currentEntry().videoId())) {
            // The active-ID hook and accepted controller must agree. Prefetched
            // responses and Shorts cannot insert entries; account playlists win above.
            PlayerType type = PlayerType.getCurrent();
            if (!PlaylistQueue.isVideoId(videoId) || type.isNoneOrHidden()
                    || type == PlayerType.INLINE_MINIMAL || hasForeignPlaybackPlaylist()) return;
            pendingSelection = null;
            if (controller.patch_getModel() != publishedModel) originalModel = controller.patch_getModel();

            // Keep the old snapshot until publish attaches the changed rows to the
            // native backend; preassigning queue would conceal the item-list change.
            if (publish(controller, queue.playNextAndSelect(videoId), false)) {
                Utils.showToastShort(str("morphe_queue_manager_fetch_succeeded_add"));
                completeMetadata();
            }

            return;
        }

        originalModel = controller.patch_getModel();
        publish(controller, queue, false);
    }

    private static void onPlaybackContextChanged() {
        Utils.runOnMainThreadNowOrLater(() -> {
            try {
                updatePlaybackContext();
            } catch (Exception ex) {
                Logger.printException(() -> "onPlaybackContextChanged failure", ex);
            }
        });
    }

    private static void updatePlaybackContext() {
        if (isNativeQueueDisabled()) {
            releaseNativeQueue();
            return;
        }

        PlayerType currentType = PlayerType.getCurrent();
        if (ShortsPlayerState.isOpen() || currentType == PlayerType.HIDDEN) pendingReload = null;
        if (pendingUndo != null && pendingUndo.closedPlayback() != null) {
            // Until native close completes, queued notifications can still describe
            // the old mini player or its dismissal animation. Once feedback is shown,
            // an active player means another session and must invalidate this Undo.
            boolean closing = pendingUndo.closedPlayback().feedbackOwner != null
                    && (currentType == PlayerType.WATCH_WHILE_MINIMIZED
                    || currentType == PlayerType.WATCH_WHILE_SLIDING_MINIMIZED_DISMISSED
                    || currentType == PlayerType.WATCH_WHILE_SLIDING_FULLSCREEN_DISMISSED);
            if (ShortsPlayerState.isOpen() || currentType == PlayerType.HIDDEN
                    || (!currentType.isNoneOrHidden() && currentType != PlayerType.INLINE_MINIMAL && !closing)) {
                pendingUndo = null;
            }
        } else if (ShortsPlayerState.isOpen() || currentType.isNoneOrHidden()
                || currentType == PlayerType.INLINE_MINIMAL) pendingUndo = null;
        if (queue != null && (ShortsPlayerState.isOpen() || PlayerType.getCurrent() == PlayerType.HIDDEN)) {
            updateShortsSession(controllerRef.get());
        }

        // The regular player can hide before Shorts attaches, and can reappear
        // before its video-ID callback. Reconcile after the native event finishes.
        Utils.runOnMainThread(() -> {
            try {
                reconcilePlaybackContext();
            } catch (Exception ex) {
                Logger.printException(() -> "reconcilePlaybackContext failure", ex);
            }
        });
    }

    private static void reconcilePlaybackContext() {
        if (queue == null) {
            showClosedQueueFeedback();
            showPendingReloadFeedback();
            tryStartQueue();
            return;
        }

        PlaylistController controller = returningController();
        boolean returningFromShorts = shortsSession.isSuspended();
        if (updateShortsSession(controller)) return;
        PlayerType type = PlayerType.getCurrent();

        // NONE describes the watch UI, not the playback service. Its
        // controller can be cleared while audio continues in the background.
        if ((type == PlayerType.INLINE_MINIMAL || (type == PlayerType.NONE
                && (controller == null || controller.patch_getVideoId().isEmpty())))
                && !serviceOwnsQueue()) releasePanel();
        else if (returningFromShorts && controller != null) updateController(controller);
    }

    /** Returns true while the retained queue must not observe or control playback. */
    private static boolean updateShortsSession(PlaylistController controller) {
        if (queue == null) return false;
        PlaylistController previous = controllerRef.get();
        PlayerType type = PlayerType.getCurrent();
        boolean shortsOpen = ShortsPlayerState.isOpen() || type == PlayerType.HIDDEN;
        if (shortsOpen || shortsSession.isSuspended()) controller = returningController();
        QueueShortsSession.Action action = shortsSession.update(ShortsPlayerState.isOpen(),
                type == PlayerType.HIDDEN, !type.isNoneOrHidden() && type != PlayerType.INLINE_MINIMAL,
                VideoInformation.getVideoId(), controller == null ? "" : controller.patch_getVideoId(),
                controller != null && hasPlaylist(controller),
                serviceOwnsQueue());
        switch (action) {
            case SUSPEND -> {
                pendingSelection = null;
                dragSession = null;
                refreshPlayerControls(previous == null ? null : previous.patch_getNavigator());
                return true;
            }

            case WAIT -> { return true; }
            case RELEASE -> {
                releasePanel();
                return true;
            }

            case RESUME -> {
                // A recreated watch controller needs its own restoration baseline.
                // Claim it only after its regular video matches active playback.
                if (controller != null && controllerRef.get() != controller) {
                    if (controller.patch_getModel() != publishedModel) {
                        originalModel = controller.patch_getModel();
                        publishedModel = null;
                    }

                    controllerRef = new WeakReference<>(controller);
                }

                shortsControllerRef.clear();
                selectionNeedsPublishing = true;
                refreshNotificationControls();
            }

            case NONE -> { }
        }

        return false;
    }

    @Nullable
    private static PlaylistController returningController() {
        PlaylistController previous = controllerRef.get();
        PlaylistController candidate = shortsControllerRef.get();
        if (candidate == null || !candidate.patch_getVideoId().equals(VideoInformation.getVideoId())) return previous;

        // Retain an attached owner over another response for the same video.
        // A confirmed account playlist must instead end the temporary queue.
        if (!hasPlaylist(candidate) && previous != null && previous.patch_isAttached()
                && previous.patch_getVideoId().equals(VideoInformation.getVideoId())) return previous;
        return candidate;
    }

    private static boolean isQueueSuspended() {
        return shortsSession.isSuspended() || ShortsPlayerState.isOpen()
                || PlayerType.getCurrent() == PlayerType.HIDDEN;
    }

    /** Resolve live playback, without treating a retained watch model as a service owner. */
    private static boolean serviceOwnsQueue() {
        if (queue == null || hasForeignPlaybackPlaylist()) return false;
        NativePlayback playback = nativePlaybackRef.get();
        if (playback != null && playback.patch_isLocalQueue()) return true;
        String videoId = queue.currentEntry().videoId();
        if (!videoId.equals(VideoInformation.getVideoId())) return false;
        for (NotificationControls controls : new ArrayList<>(notificationControls)) {
            ServiceNavigator navigator = controls.patch_getQueueMediaControls().patch_getQueueNavigator();
            if (navigator != null && videoId.equals(navigator.patch_getVideoId())) return true;
        }

        return false;
    }

    @Nullable
    private static NativePlayback playbackFor(PlaylistController controller) {
        if (controller != null && controller.patch_getNavigator() instanceof ServiceNavigator service) {
            NativePlayback playback = service.patch_getQueuePlayback();
            if (playback != null) return playback;
        }

        for (NotificationControls controls : new ArrayList<>(notificationControls)) {
            ServiceNavigator service = controls.patch_getQueueMediaControls().patch_getQueueNavigator();
            if (service == null || !VideoInformation.getVideoId().equals(service.patch_getVideoId())) continue;
            NativePlayback playback = service.patch_getQueuePlayback();
            if (playback != null) return playback;
        }

        return null;
    }

    /** Rebind after native service restoration; UI visibility does not own this sequence. */
    public static void onNativeQueueResponse(NativePlayback playback) {
        Utils.runOnMainThread(() -> {
            try {
                attachNativeQueueResponse(playback);
            } catch (Exception ex) {
                Logger.printException(() -> "onNativeQueueResponse failure", ex);
            }
        });
    }

    private static void attachNativeQueueResponse(NativePlayback playback) {
        if (isNativeQueueDisabled()) {
            releaseNativeQueue();
            return;
        }

        if (queue == null) {
            showPendingReloadFeedback();
            tryStartQueue();
            return;
        }

        if (hasForeignPlaybackPlaylist()) return;
        if (!queue.currentEntry().videoId().equals(VideoInformation.getVideoId())) {
            if (isQueueSuspended()) return;
            PlaylistController controller = readyController();
            if (controller != null && playbackFor(controller) == playback) updateController(controller);
            return;
        }

        if (playback.patch_isLocalQueue()) return;
        boolean current = playbackFor(returningController()) == playback;
        for (NotificationControls controls : new ArrayList<>(notificationControls)) {
            ServiceNavigator service = controls.patch_getQueueMediaControls().patch_getQueueNavigator();
            if (service != null && VideoInformation.getVideoId().equals(service.patch_getVideoId())
                    && service.patch_getQueuePlayback() == playback) current = true;
        }

        if (!current) return;
        if (playback.patch_attachQueue(renderer(queue), currentQueueCommand(queue))) nativePlaybackRef = new WeakReference<>(playback);
    }

    public static void onNativeQueueSelection() {
        Utils.runOnMainThread(() -> {
            try {
                if (isNativeQueueDisabled()) {
                    releaseNativeQueue();
                    return;
                }

                if (queue == null || isQueueSuspended()) return;
                syncNativeSelection(VideoInformation.getVideoId());
                refreshRepeatHeaders();
                if (selectionNeedsPublishing) refreshOwnedPanel(VideoInformation.getVideoId());
            } catch (Exception ex) {
                Logger.printException(() -> "onNativeQueueSelection failure", ex);
            }
        });
    }

    private static boolean waitingForNativeSelection(String videoId) {
        NativePlayback playback = nativePlaybackRef.get();
        return queue != null && playback != null && playback.patch_isLocalQueue()
                && !queue.currentEntry().videoId().equals(videoId)
                && queue.entries().stream().anyMatch(entry -> entry.videoId().equals(videoId));
    }

    private static void syncNativeSelection(String videoId) {
        NativePlayback playback = nativePlaybackRef.get();
        if (queue == null || playback == null || !playback.patch_isLocalQueue()) return;
        int index = playback.patch_getSelectedIndex();
        if (index < 0 || index >= queue.entries().size() || !queue.entries().get(index).videoId().equals(videoId)) return;
        pendingSelection = null;
        if (queue.selectedIndex() != index || queue.repeatMode() != playback.patch_getRepeatMode()) {
            queue = queue.select(index).withRepeatMode(playback.patch_getRepeatMode());
            selectionNeedsPublishing = true;
            playback.patch_updateSnapshot(renderer(queue));
        }
    }

    /** Runs after YouTube's click veto but before its logging decorates the endpoint. */
    public static void onRowClicked(RowClick click) {
        try {
            if (dragSession != null) return;
            pendingSelection = null;
            PlaylistController controller = readyController();
            if (queue == null || controller == null || controller.patch_getModel() != publishedModel) return;
            int index = controller.patch_findQueueRow(click.patch_getQueueEndpoint());
            if (index < 0 || index >= queue.entries().size() || index == queue.selectedIndex()) return;
            authorizeRowSelection(queue.entries().get(index));

            // Do not publish here: the original listener still needs its bound endpoint.
        } catch (Exception ex) {
            Logger.printException(() -> "onRowClicked failure", ex);
        }
    }

    private static RowSelection authorizeRowSelection(PlaylistQueue.Entry entry) {
        RowSelection selection = new RowSelection(entry);
        pendingSelection = selection;
        Utils.runOnMainThreadDelayed(() -> {
            if (pendingSelection == selection) pendingSelection = null;
        }, NAVIGATION_TIMEOUT_MILLISECONDS);
        return selection;
    }

    /** Keep the Queue label at its native text width instead of shrinking it in the counter chain. */
    public static void onPlaylistTitleBound(PlaylistTitle header) {
        // Native bind can run inside publish, before ownership and row markers are
        // committed. Recheck after it completes and after any newer rebind.
        Utils.runOnMainThread(() -> {
            try {
                TextView title = header.patch_getTitle();
                if (title == null) return;
                ViewGroup.LayoutParams params = title.getLayoutParams();
                if (params == null) return;
                PlaylistController controller = readyController();
                Object panel = header.patch_getPanel();
                boolean owned = panel != null && queue != null && publishedModel != null && controller != null
                        && controller.patch_getModel() == publishedModel
                        && panel == controller.patch_getPanel();
                if (owned) {
                    CharSequence text = title.getText();
                    if (text == null || text.length() == 0) return;
                    // ConstraintLayout normalizes the native 0dp/wrap constraint
                    // to constrained WRAP_CONTENT, which can still shrink. Use the
                    // actual styled font metrics and padding, without a fixed dp size.
                    int width = (int) Math.ceil(Layout.getDesiredWidth(text, title.getPaint()))
                            + title.getCompoundPaddingLeft() + title.getCompoundPaddingRight();
                    if (width <= 0 || params.width == width) return;
                    TitleWidth previous = originalTitleWidths.get(title);
                    originalTitleWidths.put(title,
                            new TitleWidth(previous == null ? params.width : previous.original(), width));
                    params.width = width;
                    title.setLayoutParams(params);
                } else {
                    TitleWidth width = originalTitleWidths.remove(title);
                    if (width != null && params.width == width.applied()) {
                        params.width = width.original();
                        title.setLayoutParams(params);
                    }
                }
            } catch (Exception exception) {
                Logger.printException(() -> "Could not update native queue title width", exception);
            }
        });
    }

    public static void onRepeatHeaderBound(RepeatHeader header) {
        Utils.runOnMainThreadNowOrLater(() -> {
            try {
                repeatHeaders.add(header);
                PlaylistController controller = ownedNavigator(header.patch_getNavigator());
                if (controller != null && header.patch_getPanel() == controller.patch_getPanel()) {
                    refreshRepeatHeaders();
                }
            } catch (Exception ex) {
                Logger.printException(() -> "onRepeatHeaderBound failure", ex);
            }
        });
    }

    private static void refreshRepeatHeader(RepeatHeader header) {
        PlaylistController controller = ownedNavigator(header.patch_getNavigator());
        if (controller != null && header.patch_getPanel() == controller.patch_getPanel()) {
            header.patch_enableRepeat();
            NativePlayback playback = playbackFor(controller);
            header.patch_setShuffleAvailable(playback != null
                    && playback.patch_isLocalQueue() && queue.entries().size() > 1);
        }
    }

    /** Intercept only this queue's native header; account playlist actions fall through. */
    public static boolean onQueueShuffle(RepeatHeader header) {
        try {
            PlaylistController controller = ownedNavigator(header.patch_getNavigator());
            if (controller == null || header.patch_getPanel() != controller.patch_getPanel()) return false;

            // Consume an owned click during a transition rather than enabling the hidden shuffler.
            if (publishing || pendingSelection != null || dragSession != null) return true;
            syncNativeSelection(VideoInformation.getVideoId());
            publish(controller, queue.shuffle(), true);
            return true;
        } catch (Exception ex) {
            Logger.printException(() -> "onQueueShuffle failure", ex);
            return false;
        }
    }

    private static void refreshRepeatHeaders() {
        for (RepeatHeader header : new ArrayList<>(repeatHeaders)) refreshRepeatHeader(header);
    }

    public static void onPlayerControlsCreated(PlayerControls controls) {
        Utils.runOnMainThreadNowOrLater(() -> playerControls.add(controls));
    }

    private static void refreshPlayerControls(Object navigator) {
        refreshNotificationControls();
        if (navigator == null) return;
        for (PlayerControls controls : new ArrayList<>(playerControls)) {
            if (controls.patch_getNavigator() == navigator) controls.patch_refreshNavigation();
        }
    }

    private static void refreshNotificationControls() {
        for (NotificationControls controls : new ArrayList<>(notificationControls)) {
            controls.patch_refreshQueueNotification();
        }
    }

    public static void onNotificationControlsCreated(NotificationControls controls) {
        // Finish native construction before consulting its providers or publishing
        // a refresh through the original observers.
        Utils.runOnMainThread(() -> {
            try {
                notificationControls.add(controls);
                if (queue != null) controls.patch_refreshQueueNotification();
            } catch (Exception ex) {
                Logger.printException(() -> "onNotificationControlsCreated failure", ex);
            }
        });
    }

    public static void onNotificationPlaybackChanged(NotificationControls controls) {
        try {
            // The state handler can reset navigation after selection was published.
            // Use the active service, independently of foreground video/panel callbacks.
            if (ownsNativePlayback(controls.patch_getQueueMediaControls().patch_getQueueNavigator())) {
                controls.patch_refreshQueueNotification();
            }
        } catch (Exception ex) {
            Logger.printException(() -> "onNotificationPlaybackChanged failure", ex);
        }
    }

    /** Bridge contract: bit 0 = Previous, bit 1 = Next; -1 keeps native flags. */
    public static int notificationNavigationAvailability(NotificationControls controls) {
        try {
            ServiceNavigator service = controls.patch_getQueueMediaControls().patch_getQueueNavigator();
            if (!ownsNativePlayback(service)) return -1;

            // Both native notification adapters let watch-screen delegates override
            // sequencer events. Query the same service that handles our skip clicks.
            return (service.patch_canNavigateQueue(false) ? 1 : 0)
                    | (service.patch_canNavigateQueue(true) ? 2 : 0);
        } catch (Exception ex) {
            Logger.printException(() -> "notificationNavigationAvailability failure", ex);
            return -1;
        }
    }

    public static boolean playerControlAvailability(PlayerControls controls, boolean next, boolean original) {
        try {
            if (controls.patch_getNavigator() instanceof ServiceNavigator service && ownsNativePlayback(service)) {
                return service.patch_canNavigateQueue(next);
            }

            return original;
        } catch (Exception ex) {
            Logger.printException(() -> "playerControlAvailability failure", ex);
            return original;
        }
    }

    public static boolean onNextPressed(PlayerControls controls) {
        try {
            return controls.patch_getNavigator() instanceof ServiceNavigator service && navigateNative(service, true);
        } catch (Exception ex) {
            Logger.printException(() -> "onNextPressed failure", ex);
            return false;
        }
    }

    public static boolean onPreviousPressed(PlayerControls controls) {
        try {
            return controls.patch_getNavigator() instanceof ServiceNavigator service && navigateNative(service, false);
        } catch (Exception ex) {
            Logger.printException(() -> "onPreviousPressed failure", ex);
            return false;
        }
    }

    /** Runs after native media logging, before either foreground or service skip routing. */
    public static boolean onNotificationNext(MediaControls controls) {
        try {
            return navigateNative(controls.patch_getQueueNavigator(), true);
        } catch (Exception ex) {
            Logger.printException(() -> "onNotificationNext failure", ex);
            return false;
        }
    }

    public static boolean onNotificationPrevious(MediaControls controls) {
        try {
            return navigateNative(controls.patch_getQueueNavigator(), false);
        } catch (Exception ex) {
            Logger.printException(() -> "onNotificationPrevious failure", ex);
            return false;
        }
    }

    private static boolean ownsNativePlayback(ServiceNavigator service) {
        if (isNativeQueueDisabled() || queue == null || service == null
                || ShortsPlayerState.isOpen()) return false;
        NativePlayback playback = service.patch_getQueuePlayback();
        return playback != null && playback.patch_isLocalQueue() && playback == nativePlaybackRef.get();
    }

    /** Bypass watch-history shortcuts, retaining the native queue's validation and ordering. */
    private static boolean navigateNative(ServiceNavigator service, boolean next) {
        if (!ownsNativePlayback(service)) return false;
        service.patch_navigateQueue(next);
        return true;
    }

    @Nullable
    private static PlaylistController ownedNavigator(Object navigator) {
        PlaylistController controller = readyController();
        return queue != null && controller != null && controller.patch_getModel() == publishedModel
                && controller.patch_getNavigator() == navigator ? controller : null;
    }

    /** Keep the swipe restore drawable in sync when an attached queue row changes selection. */
    public static void onSwipeBackgroundChanged(RowClick row, SwipeBackground background) {
        try {
            if (!Utils.isCurrentlyOnMainThread() || isQueueSuspended()) return;
            PlaylistController controller = controllerRef.get();
            if (controller == null || background == null) return;

            // Binding happens synchronously inside our publication, before publishedModel
            // and ownership bits are committed. Require the exact bound endpoint in the
            // controller's accepted model, including during that initial publication.
            if (!publishing && (queue == null || controller.patch_getModel() != publishedModel)) return;
            Object endpoint = row.patch_getQueueEndpoint();
            if (endpoint != null && controller.patch_findQueueRow(endpoint) >= 0) {
                background.patch_refreshQueueSwipeBackground();
            }
        } catch (Exception ex) {
            Logger.printException(() -> "onSwipeBackgroundChanged failure", ex);
        }
    }

    /** Save arguments belong to this queue-launched native fragment, including after rotation. */
    public static void onQueueSaveArguments(MessageLite endpoint, Bundle arguments) {
        try {
            ArrayList<String> videoIds = QueueSaveActions.copyVideoIds(endpoint);
            if (videoIds != null) arguments.putStringArrayList(SAVE_VIDEO_IDS_ARGUMENT, videoIds);
        } catch (Exception ex) {
            Logger.printException(() -> "onQueueSaveArguments failure", ex);
        }
    }

    @Nullable
    public static List<String> getQueueSaveVideoIds(@Nullable Bundle arguments) {
        return arguments == null ? null : arguments.getStringArrayList(SAVE_VIDEO_IDS_ARGUMENT);
    }

    /** Override only commands from an owned row or the exact published queue header. */
    @Nullable
    public static MessageLite onQueueMenuCommand(MessageLite endpoint, Map<?, ?> context) {
        if (endpoint == null || context == null) return endpoint;
        Object owner = context.get(ENDPOINT_TAG_KEY);
        boolean header = owner instanceof QueuePanel panel && panel.patch_isQueueOwned();
        if (!header && (!(owner instanceof QueueRow row) || !row.patch_isQueueOwned())) return endpoint;
        try {
            var command = PlaylistQueueRenderer.NavigationEndpoint.parseFrom(endpoint.toByteArray());
            if (header && command.hasAddToPlaylistEndpoint()) {
                PlaylistController controller = readyController();
                if (queue == null || controller == null || controller.patch_getModel() != publishedModel
                        || owner != controller.patch_getPanel()
                        || !command.getAddToPlaylistEndpoint().getVideoId().equals(queue.entries().get(0).videoId())) {
                    return null;
                }

                List<String> videoIds = new ArrayList<>();
                for (PlaylistQueue.Entry entry : queue.entries()) videoIds.add(entry.videoId());

                // The immutable selection is already serialized in this owned menu command.
                if (!command.getQueueSaveVideoIdsList().equals(videoIds)) return null;
                return endpoint;
            }

            if (!command.hasHideEnclosingAction() || command.hasWatchEndpoint()) return endpoint;
            PlaylistController controller = readyController();
            if (queue == null || controller == null || controller.patch_getModel() != publishedModel) return null;
            if (header) {
                if (owner != controller.patch_getPanel()) return null;
                NativePlayback playback = playbackFor(controller);
                if (pendingSelection != null || dragSession != null || playback == null) {
                    Utils.showToastShort(str("morphe_native_playlist_queue_unavailable"));
                    return null;
                }

                PlaylistQueue before = queue.withRepeatMode(playback.patch_getRepeatMode());
                if (!playback.patch_clearQueue()) {
                    Utils.showToastShort(str("morphe_native_playlist_queue_unavailable"));
                    return null;
                }

                releasePanel();
                return clearFeedback(endpoint, before, playback);
            }

            int index = controller.patch_findQueueRow(owner);
            if (index < 0 || index >= queue.entries().size()) return null;

            // Check again at activation: a previously revealed button must never
            // remove the current item or race an in-flight row navigation.
            if (index == queue.selectedIndex() || pendingSelection != null || dragSession != null
                    || queue.entries().get(index).videoId().equals(VideoInformation.getVideoId())) return null;
            if (!publish(controller, queue.remove(index), true)) return null;

            // A single-row removal is silent. Consume the local Hide rather than
            // dispatching an account edit or generating an Undo snackbar.
            return null;
        } catch (Exception ex) {
            Logger.printException(() -> "onQueueMenuCommand failure", ex);
            Utils.showToastShort(str("morphe_native_playlist_queue_unavailable"));
            return null;
        }
    }

    private static PendingUndo beginUndo(PlaylistQueue before, NativePlayback playback, ClosedPlayback closed) {
        PendingUndo undo = new PendingUndo(UUID.randomUUID().toString(), before, new WeakReference<>(playback), closed);
        pendingUndo = undo;

        // Bound snapshots if native feedback never reaches its snackbar factory.
        expireUndo(undo, ACCESSIBLE_UNDO_DURATION_MILLISECONDS);
        return undo;
    }

    private static byte[] clearFeedback(PendingUndo undo) {
        return QueueUndoActions.feedback(undo.token(), str("morphe_native_playlist_queue_cleared"),
                str("offline_undo_snackbar_button_text")).toByteArray();
    }

    private static MessageLite clearFeedback(MessageLite endpoint, PlaylistQueue before, NativePlayback playback) {
        PendingUndo undo = beginUndo(before, playback, null);
        return ((QueueUndoActions.Command) endpoint).patch_parseQueueCommand(
                clearFeedback(undo));
    }

    /** Returns a request-bound callback for Reload's actual intent dispatch, or null if no queue was cleared. */
    @Nullable
    public static Runnable prepareVideoReload(String videoId) {
        try {
            if (isNativeQueueDisabled()) return null;
            // Reload's dismissal/open is delayed. Cancel older capabilities now,
            // before they can restore a queue into the player being replaced.
            pendingUndo = null;
            pendingReload = null;
            clearPendingQueueStart();
            OpenVideosFullscreenHookPatch.keepVideoMinimized(null);
            if (queue == null || !queue.currentEntry().videoId().equals(videoId)) return null;
            PlaylistController controller = readyController();
            if (controller == null || controller.patch_getModel() != publishedModel
                    || pendingSelection != null || dragSession != null) return null;
            NativePlayback playback = playbackFor(controller);
            if (playback == null || !playback.patch_isLocalQueue()) return null;
            PlaylistQueue before = queue.withRepeatMode(playback.patch_getRepeatMode());
            if (!playback.patch_clearQueue()) {
                Logger.printException(() -> "Could not clear native queue before reloading the video");
                return null;
            }

            releasePanel();
            convertedPlaylist = null;
            PendingReload reload = new PendingReload(before);
            pendingReload = reload;
            // Do not retain a queue indefinitely if Reload never opens its player.
            Utils.runOnMainThreadDelayed(() -> {
                if (pendingReload == reload) pendingReload = null;
            }, NAVIGATION_TIMEOUT_MILLISECONDS);
            return () -> {
                if (pendingReload == reload) reload.opening = true;
            };
        } catch (Exception exception) {
            pendingReload = null;
            Logger.printException(() -> "Could not prepare native queue Reload Undo", exception);
            return null;
        }
    }

    private static void showPendingReloadFeedback() {
        PendingReload reload = pendingReload;
        if (reload == null || !reload.opening) return;
        PlaylistController controller = readyController();
        if (controller == null || controller != reload.controller.get()
                || !reload.before.currentEntry().videoId().equals(VideoInformation.getVideoId())
                || hasForeignPlaybackPlaylist()) return;
        NativePlayback playback = playbackFor(controller);
        if (playback == null || playback.patch_isLocalQueue()) return;
        pendingReload = null;
        PendingUndo undo = beginUndo(reload.before, playback, null);
        try {
            controller.patch_showQueueFeedback(clearFeedback(undo));
        } catch (Exception exception) {
            if (pendingUndo == undo) pendingUndo = null;
            Logger.printException(() -> "Could not show native queue reload confirmation", exception);
        }
    }

    /** Called from accepted native mini-player close clicks or gestures before teardown. */
    public static void onQueuePlayerClosing() {
        try {
            if (queue == null) return;
            PlaylistController controller = readyController();
            if (controller == null || controller.patch_getModel() != publishedModel
                    || pendingSelection != null || dragSession != null) return;
            NativePlayback playback = playbackFor(controller);
            long position = VideoInformation.getVideoTime();
            if (playback == null || !playback.patch_isLocalQueue() || position < 0) return;
            PlaylistQueue before = queue.withRepeatMode(playback.patch_getRepeatMode());

            // Detach our local sequence while the original close listener still owns
            // stopping playback. Never keep a disposed backend as the restore target.
            if (!playback.patch_clearQueue()) {
                Logger.printException(() -> "Could not detach native queue before closing the player");
                return;
            }

            releasePanel();
            beginUndo(before, null, new ClosedPlayback(position, controller));

            // The original native dismissal runs before this posted callback.
            Utils.runOnMainThread(NativePlaylistQueuePatch::showClosedQueueFeedback);
        } catch (Exception ex) {
            Logger.printException(() -> "onQueuePlayerClosing failure", ex);
        }
    }

    private static void showClosedQueueFeedback() {
        PendingUndo undo = pendingUndo;
        if (undo == null || undo.closedPlayback() == null || queue != null) return;
        if (isNativeQueueDisabled() || ShortsPlayerState.isOpen()) {
            pendingUndo = null;
            return;
        }

        PlayerType type = PlayerType.getCurrent();
        if (type != PlayerType.NONE && type != PlayerType.INLINE_MINIMAL) return;
        PlaylistController owner = undo.closedPlayback().feedbackOwner;
        if (owner == null) return;

        // The activity dispatcher remains callable after its watch controller detaches.
        // Clear this reference so repeated teardown notifications cannot show it twice.
        undo.closedPlayback().feedbackOwner = null;
        try {
            owner.patch_showQueueFeedback(clearFeedback(undo));
        } catch (Exception exception) {
            if (pendingUndo == undo) pendingUndo = null;
            Logger.printException(() -> "Could not show native queue player-close confirmation", exception);
        }
    }

    /** Called by the native snackbar factory only for a matching local Undo capability. */
    static int queueUndoDuration(String token) {
        PendingUndo undo = pendingUndo;
        if (undo == null || !undo.token().equals(token)) return 0;
        AccessibilityManager accessibility = (AccessibilityManager)
                Utils.getContext().getSystemService(Context.ACCESSIBILITY_SERVICE);

        // Match YouTube's native snackbar accessibility predicate on supported versions.
        boolean accessible = accessibility != null && accessibility.isEnabled()
                && accessibility.isTouchExplorationEnabled();

        // A new identity prevents an earlier timer from expiring re-created feedback.
        PendingUndo presented = new PendingUndo(undo.token(), undo.before(), undo.playback(), undo.closedPlayback());
        pendingUndo = presented;
        expireUndo(presented, accessible ? ACCESSIBLE_UNDO_DURATION_MILLISECONDS : UNDO_DURATION_MILLISECONDS);

        // Leave the accessible native action timeout at 20 seconds. Setting a positive
        // 20-second duration would make YouTube add another 10 seconds for accessibility.
        return accessible ? 0 : UNDO_DURATION_MILLISECONDS;
    }

    private static void expireUndo(PendingUndo undo, int duration) {
        Utils.runOnMainThreadDelayed(() -> {
            if (pendingUndo == undo) pendingUndo = null;
        }, duration);
    }

    /** Native snackbar activation is not a row/menu action and need not retain that owner. */
    public static boolean onQueueUndoCommand(MessageLite endpoint) {
        PendingUndo undo = pendingUndo;
        try {
            return QueueUndoActions.dispatchUndo(endpoint, undo == null ? null : undo.token(), () -> {
                if (undo != null) undoQueueEdit(undo.token());
            });
        } catch (Exception ex) {
            Logger.printException(() -> "onQueueUndoCommand failure", ex);
            return false;
        }
    }

    private static void undoQueueEdit(String token) {
        PendingUndo undo = pendingUndo;
        if (undo == null || !undo.token().equals(token)) return;
        pendingUndo = null; // A native callback can only activate this capability once.
        if (undo.closedPlayback() != null) {
            if (!canStartQueue() || pendingQueueStart != null) return;
            startQueue(new QueueStart(undo.before(), VideoInformation.getVideoId(),
                    undo.closedPlayback().positionMilliseconds));
            return;
        }

        PlaylistController controller = readyController();
        if (controller == null) return;
        if (pendingSelection != null || dragSession != null) return;
        if (hasForeignPlaybackPlaylist()) return;
        NativePlayback playback = undo.playback().get();
        if (playback == null) return;
        if (playbackFor(controller) != playback) return;
        PlaylistQueue restored = QueueUndoActions.restore(undo.before(), queue,
                VideoInformation.getVideoId());
        if (restored == null) return;
        Object baseline = controller.patch_getModel();
        if (!publish(controller, restored, true)) return;
        originalModel = baseline;
        playback.patch_restoreQueueRepeat(restored.repeatMode());
        queue = queue.withRepeatMode(playback.patch_getRepeatMode());
    }

    /** Native row overflow creates its menu context directly, outside shared feed hooks. */
    public static void onRowMenuOpened(Map<?, ?> context) {
        try {
            if (context == null) return;
            Object row = context.get(ENDPOINT_TAG_KEY);
            if (row instanceof FlyoutUtils.FlyoutMenuVideoIdInterface
                    && (!isNativeQueueDisabled() || (row instanceof QueueRow owned && owned.patch_isQueueOwned()))) {
                FlyoutUtils.extractFlyoutIdFromMap(context);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "onRowMenuOpened failure", ex);
        }
    }

    /** Observe native start/end notifications; never replace the gesture listeners. */
    public static void onReorderStarted(Object holder, ReorderItems items, int index) {
        try {
            PlaylistController controller = readyController();
            if (queue == null || controller == null || pendingSelection != null
                    || controller.patch_getModel() != publishedModel
                    || items.patch_getSize() != queue.entries().size()
                    || index < 0 || index >= items.patch_getSize()) return;
            Object row = items.patch_getQueueRow(index);
            if (row instanceof QueueRow owned && owned.patch_isQueueOwned()
                    && controller.patch_findQueueRow(row) == index) {
                dragSession = new DragSession(holder, items, row, index);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "onReorderStarted failure", ex);
        }
    }

    public static void onReorderEnded(Object holder, ReorderItems items, int from, int to) {
        DragSession session = dragSession;
        if (session == null || session.holder != holder || session.items != items) return;
        session.ended = true;

        // Let all native observers and coordinator cleanup finish. Hold off metadata
        // publication until then so it cannot rebind the adapter in mid-gesture.
        Utils.runOnMainThread(() -> {
            try {
                if (dragSession != session) return;
                dragSession = null;
                PlaylistController controller = readyController();
                if (queue != session.snapshot || controller == null
                        || controller.patch_getModel() != session.model) return;
                boolean validMove = from == session.from && to >= 0 && to < items.patch_getSize()
                        && items.patch_getSize() == queue.entries().size()
                        && items.patch_getQueueRow(to) == session.row;
                PlaylistQueue candidate = validMove ? queue.move(from, to) : queue;
                if (!publish(controller, candidate, validMove)) publish(controller, session.snapshot, false);
            } catch (Exception ex) {
                Logger.printException(() -> "onReorderEnded failure", ex);
            }
        });
    }

    /** Native teardown can cancel a drag without an end notification. */
    public static void onReorderHolderUnbound(Object holder) {
        DragSession session = dragSession;
        if (session == null || session.holder != holder || session.ended) return;
        dragSession = null;
        Utils.runOnMainThread(() -> {
            try {
                if (dragSession == null && queue == session.snapshot) {
                    refreshOwnedPanel(session.snapshot.currentEntry().videoId());
                }
            } catch (Exception ex) {
                Logger.printException(() -> "onReorderHolderUnbound failure", ex);
            }
        });
    }

    /** Shared video-ID hook; prefetched player responses must not change this state. */
    public static void onVideoChanged(String videoId) {
        Utils.runOnMainThread(() -> {
            try {
                updateVideo(videoId);
            } catch (Exception ex) {
                Logger.printException(() -> "onVideoChanged failure", ex);
            }
        });
    }

    private static void updateVideo(String videoId) {
        if (!videoId.equals(VideoInformation.getVideoId())) return;
        if (isNativeQueueDisabled()) {
            releaseNativeQueue();
            return;
        }

        if (pendingReload != null && !PlayerType.getCurrent().isNoneOrHidden()
                && PlayerType.getCurrent() != PlayerType.INLINE_MINIMAL
                && !pendingReload.before.currentEntry().videoId().equals(videoId)) pendingReload = null;
        showPendingReloadFeedback();

        // A cleared queue belongs to the uninterrupted ordinary playback session.
        // Returning to its video later must not revive an earlier Undo action.
        if (pendingUndo != null && pendingUndo.closedPlayback() == null
                && !pendingUndo.before().currentEntry().videoId().equals(videoId)) pendingUndo = null;
        tryStartQueue();
        if (queue == null) return;

        // This is a presentation snapshot of native selection, not a second navigator.
        syncNativeSelection(videoId);
        boolean returningFromShorts = shortsSession.isSuspended();
        PlaylistController returningController = returningController();
        if (updateShortsSession(returningController)) return;
        if (returningFromShorts) {
            if (returningController != null) updateController(returningController);
            return;
        }

        if (waitingForNativeSelection(videoId)) return;
        if (pendingSelection != null && pendingSelection.entry().videoId().equals(videoId)) {
            for (int index = 0; index < queue.entries().size(); index++) {
                if (queue.entries().get(index) != pendingSelection.entry()) continue;
                dragSession = null;
                queue = queue.select(index);
                selectionNeedsPublishing = true;
                pendingSelection = null;

                // Let the original navigation/response callback finish first.
                Utils.runOnMainThread(() -> {
                    try {
                        refreshNotificationControls();
                        PlaylistController controller = readyController();
                        if (controller == null) return;
                        if (controller.patch_getModel() == publishedModel) refreshOwnedPanel(videoId);
                        else updateController(controller);
                    } catch (Exception ex) {
                        Logger.printException(() -> "Selection refresh failure", ex);
                    }
                });
                return;
            }
        }

        if (!queue.currentEntry().videoId().equals(videoId)) {
            // The active ID can arrive before its watch controller. Retain the
            // sequence until that controller confirms the new regular video.
            if (returningController != null) updateController(returningController);
        }
    }

    /** Captures the eligible native menu action for the selected video. */
    @Nullable
    public static Runnable getFlyoutQueueAction(String videoId, boolean playLast) {
        if (isNativeQueueDisabled() || !PlaylistQueue.isVideoId(videoId)) return null;
        // Claim every stock Next/Last click in Native mode. Readiness is checked
        // by the action; a temporarily unavailable controller must not open Premium.
        return () -> addToQueue(videoId, playLast);
    }

    /** Only our Native queue makes Last available; account playlists and Mixes do not. */
    public static boolean canShowFlyoutLast() {
        return queue != null;
    }

    @Nullable
    private static PlaylistController activePlaylistController() {
        PlaylistController controller = controllerRef.get();
        PlayerType type = PlayerType.getCurrent();
        return controller != null && controller.patch_isAttached() && hasPlaylist(controller)
                && !type.isNoneOrHidden() && type != PlayerType.INLINE_MINIMAL
                && !ShortsPlayerState.isOpen()
                && controller.patch_getVideoId().equals(VideoInformation.getVideoId()) ? controller : null;
    }

    private static boolean isNativeQueueDisabled() {
        return Settings.QUEUE_TYPE.get() != QueueType.NATIVE;
    }

    /** Release only our session; never modify the Morphe queue or its settings. */
    private static void releaseNativeQueue() {
        convertedPlaylist = null;
        pendingReload = null;
        clearPendingQueueStart();
        pendingUndo = null;
        OpenVideosFullscreenHookPatch.keepVideoMinimized(null);
        if (queue == null) return;
        NativePlayback playback = nativePlaybackRef.get();
        try {
            // Use the same native transaction as Clear: no load, seek, pause or stop.
            if (playback != null && playback.patch_isLocalQueue() && !playback.patch_clearQueue()) {
                Logger.printException(() -> "Could not release native queue");
                return;
            }

            releasePanel();
        } catch (Exception exception) {
            Logger.printException(() -> "releaseNativeQueue failure", exception);
        }
    }

    private static boolean canStartQueue() {
        PlayerType type = PlayerType.getCurrent();
        return !isNativeQueueDisabled() && queue == null && !ShortsPlayerState.isOpen()
                && (type == PlayerType.NONE || type == PlayerType.INLINE_MINIMAL);
    }

    @Nullable
    private static PlaylistController readyController() {
        PlaylistController controller = controllerRef.get();
        if (isNativeQueueDisabled() || isQueueSuspended() || controller == null
                || !controller.patch_isAttached() || hasPlaylist(controller)
                || PlayerType.getCurrent().isNoneOrHidden()
                || PlayerType.getCurrent() == PlayerType.INLINE_MINIMAL
                || !controller.patch_getVideoId().equals(VideoInformation.getVideoId())) {
            return null;
        }

        return controller;
    }

    private static boolean hasForeignPlaybackPlaylist() {
        String playlistId = VideoInformation.getPlaylistId();
        if (playlistId.isEmpty()) return false;
        ConvertedPlaylist converted = convertedPlaylist;
        if (converted == null || isNativeQueueDisabled()
                || !converted.videoId().equals(VideoInformation.getVideoId())
                || !converted.playlistId().equals(playlistId)) return true;
        NativePlayback playback = converted.playback().get();
        if (playback == null) return true;
        // The service can retain our queue after its watch UI is collected.
        if (queue != null && nativePlaybackRef.get() == playback && playback.patch_isLocalQueue()) return false;
        return playback != playbackFor(returningController());
    }

    /** Keep a late response for the replaced list from reclaiming this same player. */
    public static boolean shouldIgnorePlaylistResponse(NativePlayback playback, MessageLite endpoint) {
        ConvertedPlaylist converted = convertedPlaylist;
        if (converted == null || isNativeQueueDisabled() || endpoint == null
                || converted.playback().get() != playback
                || !converted.videoId().equals(VideoInformation.getVideoId())) return false;
        try {
            var command = PlaylistQueueRenderer.NavigationEndpoint.parseFrom(endpoint.toByteArray());
            return command.hasWatchEndpoint()
                    && converted.videoId().equals(command.getWatchEndpoint().getVideoId())
                    && converted.playlistId().equals(command.getWatchEndpoint().getPlaylistId());
        } catch (Exception exception) {
            Logger.printException(() -> "Could not inspect replaced playlist response", exception);
            return false;
        }
    }

    /** Called after native stale-video validation, before storing the incoming model. */
    public static boolean shouldIgnorePlaylistModel(PlaylistController controller, PlaylistModel model) {
        ConvertedPlaylist converted = convertedPlaylist;
        return converted != null && !isNativeQueueDisabled() && !publishing
                && converted.videoId().equals(VideoInformation.getVideoId())
                && converted.videoId().equals(controller.patch_getVideoId())
                && converted.playlistId().equals(model.patch_getPlaylistId())
                && converted.playback().get() != null
                && converted.playback().get() == playbackFor(controller);
    }

    private static boolean hasPlaylist(PlaylistController controller) {
        String playlistId = controller.patch_getPlaylistId();
        return playlistId != null && !playlistId.isEmpty();
    }

    private static void addToQueue(String videoId, boolean playLast) {
        if (isNativeQueueDisabled()) {
            releaseNativeQueue();
            return;
        }
        if (playLast && queue == null) {
            // A menu callback can outlive the queue that made Play last available.
            Utils.showToastShort(str("morphe_native_playlist_queue_not_ready"));
            return;
        }

        PlaylistController playlist = activePlaylistController();
        String channelName = videoId.equals(FlyoutUtils.getFlyoutVideoId())
                ? FlyoutUtils.getFlyoutChannelName() : "";
        try {
            if (playlist != null) {
                if (dragSession != null || queue != null) {
                    Utils.showToastShort(str("morphe_native_playlist_queue_not_ready"));
                    return;
                }
                NativePlayback playback = playbackFor(playlist);
                if (playback == null) {
                    Utils.showToastShort(str("morphe_native_playlist_queue_unavailable"));
                    return;
                }
                String currentId = playlist.patch_getVideoId();
                String currentTitle = VideoInformation.getVideoTitle();
                String title = ChannelIdRequest.getVideoTitleIfAvailable(videoId);
                rememberChannel(currentId, currentTitle, VideoInformation.getChannelName());
                rememberChannel(videoId, title.isEmpty() ? videoId : title, channelName);
                PlaylistQueue candidate = new PlaylistQueue(currentId, currentTitle);
                if (!currentId.equals(videoId)) candidate = candidate.playNext(videoId);
                // The native accept transaction replaces navigation rows without a
                // load, seek, pause or player dismissal. Reuse the mutable navigator
                // when available; its factory receives only our list-free command.
                String sourcePlaylistId = playlist.patch_getPlaylistId();
                if (publish(playlist, candidate, true)) {
                    convertedPlaylist = new ConvertedPlaylist(currentId, sourcePlaylistId, new WeakReference<>(playback));
                    // Clear must hide the replaced playlist, not restore its old rows.
                    originalModel = null;
                    refreshPlayerControls(playlist.patch_getNavigator());
                    QueueFullscreenActions.refresh();
                    FlyoutUtils.dismissFlyout();
                    Utils.showToastShort(str("morphe_queue_manager_fetch_succeeded_add"));
                    completeMetadata();
                }
                return;
            }
            if (!playLast && canStartQueue()) {
                String title = ChannelIdRequest.getVideoTitleIfAvailable(videoId);
                rememberChannel(videoId, title.isEmpty() ? videoId : title, channelName);
                QueueStart start = new QueueStart(new PlaylistQueue(videoId, title), VideoInformation.getVideoId());

                // The shared helper opens YouTube's own watch intent. With false it
                // does not call a native player bridge or require an existing player.
                startQueue(start);
                FlyoutUtils.dismissFlyout();
                return;
            }

            PlaylistController controller = readyController();
            if (controller == null || dragSession != null) {
                Utils.showToastShort(str("morphe_native_playlist_queue_not_ready"));
                return;
            }

            rememberChannel(controller.patch_getVideoId(),
                    VideoInformation.getVideoTitle(), VideoInformation.getChannelName());
            String title = ChannelIdRequest.getVideoTitleIfAvailable(videoId);
            rememberChannel(videoId, title.isEmpty() ? videoId : title, channelName);
            PlaylistQueue candidate = queue == null
                    ? new PlaylistQueue(controller.patch_getVideoId(), VideoInformation.getVideoTitle())
                    : queue;
            if (queue == null) originalModel = controller.patch_getModel();
            // The first queue already contains the playing video. An explicit
            // addition to an existing queue can still create another occurrence.
            PlaylistQueue updated = queue == null && controller.patch_getVideoId().equals(videoId)
                    ? candidate : playLast ? candidate.playLast(videoId) : candidate.playNext(videoId);
            if (publish(controller, updated, true)) {
                FlyoutUtils.dismissFlyout();
                Utils.showToastShort(str("morphe_queue_manager_fetch_succeeded_add"));
                completeMetadata();
            }
        } catch (Exception exception) {
            Logger.printException(() -> "Could not add video to native playlist queue", exception);
            Utils.showToastShort(str("morphe_native_playlist_queue_unavailable"));
        }
    }

    private static void clearPendingQueueStart() {
        int blockId = pendingQueueStartInlinePlaybackBlockId;
        pendingQueueStart = null;
        pendingQueueStartInlinePlaybackBlockId = 0;
        if (blockId != 0) LoadVideoPatch.releaseInlinePlaybackBlock(blockId);
    }

    private static void startQueue(QueueStart start) {
        clearPendingQueueStart();
        pendingReload = null;
        pendingUndo = null;
        pendingQueueStart = start;
        Utils.runOnMainThreadDelayed(() -> {
            if (pendingQueueStart != start) return;
            clearPendingQueueStart();
            OpenVideosFullscreenHookPatch.keepVideoMinimized(null);
        }, NAVIGATION_TIMEOUT_MILLISECONDS);

        // A focused feed preview must not take ownership of closed-player Undo.
        if (start.restoresClosedPlayer()) {
            pendingQueueStartInlinePlaybackBlockId = LoadVideoPatch.blockInlinePlaybackUntilPlayerOpens();
        }

        try {
            LoadVideoPatch.openVideoIntent(start.watchUrl(), false);
        } catch (RuntimeException exception) {
            if (pendingQueueStart == start) {
                clearPendingQueueStart();
                OpenVideosFullscreenHookPatch.keepVideoMinimized(null);
            }
            throw exception;
        }
    }

    /** Commit a first-item queue only after native playback accepts the requested video. */
    private static void tryStartQueue() {
        if (isNativeQueueDisabled()) {
            releaseNativeQueue();
            return;
        }

        QueueStart start = pendingQueueStart;
        if (start == null || queue != null || publishing) return;
        String videoId = VideoInformation.getVideoId();
        String requestedId = start.candidate().currentEntry().videoId();
        PlayerType type = PlayerType.getCurrent();
        if (ShortsPlayerState.isOpen() || type == PlayerType.HIDDEN
                || (!type.isNoneOrHidden() && type != PlayerType.INLINE_MINIMAL
                && start.isSupersededBy(videoId))) {
            clearPendingQueueStart();
            OpenVideosFullscreenHookPatch.keepVideoMinimized(null);
            return;
        }

        PlaylistController controller = controllerRef.get();

        // Controller callbacks can precede the active video ID or player type.
        // Recheck when controller, video ID, player type or playback service
        // becomes ready; prefetch alone cannot attach.
        if (controller == null || !requestedId.equals(videoId)
                || !requestedId.equals(controller.patch_getVideoId())) return;
        if (hasPlaylist(controller)) {
            clearPendingQueueStart();
            OpenVideosFullscreenHookPatch.keepVideoMinimized(null);
            return;
        }

        if (readyController() != controller) return;
        Object baseline = controller.patch_getModel();
        if (publish(controller, start.candidate(), false)) {
            originalModel = baseline;
            clearPendingQueueStart();
            if (start.restoresClosedPlayer()) {
                NativePlayback playback = nativePlaybackRef.get();
                playback.patch_restoreQueueRepeat(start.candidate().repeatMode());
                queue = queue.withRepeatMode(playback.patch_getRepeatMode());
                refreshRepeatHeaders();
                refreshPlayerControls(controller.patch_getNavigator());
            } else Utils.showToastShort(str("morphe_queue_manager_fetch_succeeded_add"));
            completeMetadata();
        }
    }

    private static void completeMetadata() {
        for (PlaylistQueue.Entry entry : queue.entries()) {
            VideoMetadata metadata = metadataCache.get(entry.videoId());
            if (metadata != null && metadata.isComplete()) continue;
            Locale locale = Locale.getDefault();
            String key = entry.videoId() + ":" + locale.toLanguageTag();
            if (!pendingMetadata.add(key)) continue;
            String knownTitle = ChannelIdRequest.getVideoTitleIfAvailable(entry.videoId());
            String capturedTitle = metadata == null ? entry.title() : metadata.title();
            String searchTitle = knownTitle.isEmpty() ? capturedTitle : knownTitle;
            Utils.runOnBackgroundThread(() -> {
                VideoMetadata fetched = PlaylistMetadataRequest.fetch(entry.videoId(), searchTitle, locale);
                Utils.runOnMainThread(() -> {
                    pendingMetadata.remove(key);
                    if (fetched == null || !Locale.getDefault().equals(locale)) return;
                    metadataCache.compute(entry.videoId(), (videoId, previous) ->
                            previous == null ? fetched : previous.withLookup(fetched));
                    refreshOwnedPanel(entry.videoId());
                });
            });
        }
    }

    private static void refreshOwnedPanel(String videoId) {
        if (dragSession != null || queue == null
                || queue.entries().stream().noneMatch(item -> item.videoId().equals(videoId))) return;
        PlaylistController controller = readyController();
        if (controller != null && controller.patch_getModel() == publishedModel) publish(controller, queue, false);
    }

    /** Keep an individual music-video tap in our queue instead of starting its automatic Mix. */
    public static MessageLite prepareQueueVideoCommand(MessageLite endpoint, Map<?, ?> context) {
        if (!Utils.isCurrentlyOnMainThread() || queue == null || publishing
                || pendingSelection != null || dragSession != null || context == null
                || !(endpoint instanceof QueueUndoActions.Command nativeCommand)) return endpoint;
        PlaylistController controller = readyController();
        if (controller == null || controller.patch_getModel() != publishedModel) return endpoint;
        NativePlayback playback = playbackFor(controller);
        if (playback == null || playback != nativePlaybackRef.get() || !playback.patch_isLocalQueue()) return endpoint;
        try {
            var command = PlaylistQueueRenderer.NavigationEndpoint.parseFrom(endpoint.toByteArray());
            if (!command.hasWatchEndpoint()) return endpoint;
            var watch = command.getWatchEndpoint();
            if (!PlaylistQueue.isVideoId(watch.getVideoId()) || !watch.getPlaylistId().startsWith("RD")
                    || watch.hasIndex() || !watch.getPlaylistSetVideoId().isEmpty()) return endpoint;
            Object sender = context.get("com.google.android.libraries.youtube.rendering.elements.sender_view");
            if (!(sender instanceof View view) || !FeedVideoCardsPatch.isIndividualVideoTap(view)) return endpoint;

            // Only the automatic playlist is removed. Retain the original video,
            // player parameters, tracking and unknown fields, then use the native
            // registry to rebuild the command before descriptor construction.
            // Existing video-change handling selects/inserts it in the queue.
            var prepared = command.toBuilder().setWatchEndpoint(watch.toBuilder().clearPlaylistId()).build();
            return nativeCommand.patch_parseQueueCommand(prepared.toByteArray());
        } catch (Exception ex) {
            Logger.printException(() -> "prepareQueueVideoCommand failure", ex);
            return endpoint;
        }
    }

    /** Native watch dispatch reads this flag before constructing the playback descriptor. */
    public static Map<?, ?> prepareQueueStartContext(MessageLite endpoint, Map<?, ?> context) {
        if (endpoint == null) return context;
        QueueStart start = pendingQueueStart;
        try {
            var command = PlaylistQueueRenderer.NavigationEndpoint.parseFrom(endpoint.toByteArray());
            // A deliberate new watch supersedes Clear/close/reload Undo, even for
            // the same video. This hook runs on dispatch, not on prefetch.
            if (command.hasWatchEndpoint()) {
                pendingUndo = null;
                PendingReload reload = pendingReload;
                if (reload != null) {
                    var watch = command.getWatchEndpoint();
                    if (reload.opening && !reload.watchDispatched && watch.getPlaylistId().isEmpty()
                            && reload.before.currentEntry().videoId().equals(watch.getVideoId())) {
                        reload.watchDispatched = true;
                    } else pendingReload = null;
                }
            }

            if (convertedPlaylist != null && command.hasWatchEndpoint()
                    && !command.getWatchEndpoint().getPlaylistId().isEmpty()) {
                // This hook observes dispatch, not prefetch. An explicit playlist
                // navigation, even to the same video/list, supersedes conversion.
                releaseNativeQueue();
            }
            if (isNativeQueueDisabled() || start == null || queue != null) return context;
            Map<?, ?> minimized = start.minimizedContext(endpoint, context);
            if (minimized != context) {
                OpenVideosFullscreenHookPatch.keepVideoMinimized(
                        start.candidate().currentEntry().videoId());
            }

            return minimized;
        } catch (Exception ex) {
            Logger.printException(() -> "prepareQueueStartContext failure", ex);
            return context;
        }
    }

    private static byte[] currentQueueCommand(PlaylistQueue candidate) {
        // The navigation factory must not inherit the playing video's old list ID.
        // This describes navigation only; it is never loaded into the running player.
        return PlaylistQueueRenderer.NavigationEndpoint.newBuilder().setWatchEndpoint(
                PlaylistQueueRenderer.WatchEndpoint.newBuilder()
                        .setVideoId(candidate.currentEntry().videoId())).build().toByteArray();
    }

    private static boolean publish(PlaylistController controller, PlaylistQueue candidate, boolean reportFailure) {
        publishing = true;
        Object previousModel = controller.patch_getModel();
        Object acceptedModel = null;
        boolean committed = false;
        try {
            NativePlayback playback = playbackFor(controller);
            if (playback == null) {
                if (reportFailure) Utils.showToastShort(str("morphe_native_playlist_queue_unavailable"));
                return false;
            }

            byte[] panel = renderer(candidate);
            acceptedModel = controller.patch_updatePanel(panel);
            if (acceptedModel == null) {
                if (reportFailure) Utils.showToastShort(str("morphe_native_playlist_queue_unavailable"));
                return false;
            }

            boolean changedItems = queue == null || queue.entries().size() != candidate.entries().size();
            if (!changedItems) {
                // Equal video/title values can still be different occurrences of a
                // duplicated video. A shuffle must also move the native selection.
                for (int index = 0; index < candidate.entries().size(); index++) {
                    if (queue.entries().get(index) != candidate.entries().get(index)) {
                        changedItems = true;
                        break;
                    }
                }
            }

            if (!playback.patch_isLocalQueue() || changedItems) {
                if (!playback.patch_attachQueue(panel, currentQueueCommand(candidate))) {
                    controller.patch_restorePanel(acceptedModel, previousModel);
                    if (reportFailure) Utils.showToastShort(str("morphe_native_playlist_queue_unavailable"));
                    return false;
                }
            } else playback.patch_updateSnapshot(panel);
            nativePlaybackRef = new WeakReference<>(playback);

            // The native sequencer, including its inherited Loop mode, is authoritative.
            candidate = candidate.withRepeatMode(playback.patch_getRepeatMode());
            queue = candidate;
            pendingReload = null;
            if (changedItems) pendingUndo = null;
            publishedModel = acceptedModel;
            selectionNeedsPublishing = false;

            // From here the panel, playback backend and local snapshot agree.
            // A presentation refresh cannot roll back only one of those owners.
            committed = true;
            controller.patch_markQueueRows();
            refreshRepeatHeaders();
            refreshPlayerControls(controller.patch_getNavigator());
            QueueFullscreenActions.refresh();
            return true;
        } catch (Exception exception) {
            if (committed) {
                Logger.printException(() -> "Could not refresh native playlist queue controls", exception);
                return true;
            }

            if (acceptedModel != null && controller.patch_getModel() == acceptedModel) {
                controller.patch_restorePanel(acceptedModel, previousModel);
            }

            Logger.printException(() -> "Could not display native playlist queue", exception);
            if (reportFailure) Utils.showToastShort(str("morphe_native_playlist_queue_unavailable"));
            return false;
        } finally {
            publishing = false;
        }
    }

    private static void releasePanel() {
        pendingReload = null;
        pendingUndo = null;
        clearPendingQueueStart();
        OpenVideosFullscreenHookPatch.keepVideoMinimized(null);
        PlaylistController controller = controllerRef.get();
        Object expectedModel = publishedModel;
        Object restoreModel = originalModel;
        Object navigator = controller == null ? null : controller.patch_getNavigator();
        queue = null;
        nativePlaybackRef.clear();
        shortsSession.reset();
        shortsControllerRef.clear();
        dragSession = null;
        pendingSelection = null;
        originalModel = null;
        publishedModel = null;
        selectionNeedsPublishing = false;

        // A null baseline is valid when YouTube had no playlist before our display.
        if (controller != null && expectedModel != null) {
            try {
                controller.patch_restorePanel(expectedModel, restoreModel);
            } catch (Exception exception) {
                Logger.printException(() -> "Could not restore native playlist panel", exception);
            }
        }

        refreshPlayerControls(navigator);
        QueueFullscreenActions.refresh();
    }

    static boolean ownsFullscreenPlaylist() {
        PlaylistController controller = controllerRef.get();
        NativePlayback playback = nativePlaybackRef.get();

        // Model/backend ownership survives a hidden watch screen. YouTube's native
        // quick-actions controller, rather than this binding, decides visibility.
        return !isNativeQueueDisabled() && queue != null && publishedModel != null && controller != null
                && controller.patch_getModel() == publishedModel
                && playback != null && playback.patch_isLocalQueue();
    }

    private static PlaylistQueueRenderer.SwipeAction removalAction() {
        var label = PlaylistQueueRenderer.Accessibility.newBuilder().setAccessibilityData(
                PlaylistQueueRenderer.AccessibilityLabel.newBuilder().setLabel(str("remove")));
        var endpoint = PlaylistQueueRenderer.NavigationEndpoint.newBuilder().setHideEnclosingAction(
                PlaylistQueueRenderer.HideEnclosingAction.newBuilder().setHack(true));
        var button = PlaylistQueueRenderer.SwipeButton.newBuilder()
                .setStyle(PlaylistQueueRenderer.SwipeButton.Style.BLACK_FILLED)
                .setSize(PlaylistQueueRenderer.SwipeButton.Size.DEFAULT).setIsDisabled(false)
                .setIcon(Icon.newBuilder().setYtIconType(YTIconType.DELETE))
                .setAccessibilityData(label).setServiceEndpoint(endpoint);
        return PlaylistQueueRenderer.SwipeAction.newBuilder().setButtonRenderer(button).build();
    }

    private static PlaylistQueueRenderer.Menu playlistMenu(PlaylistQueue source) {
        List<String> videoIds = new ArrayList<>();
        for (PlaylistQueue.Entry entry : source.entries()) videoIds.add(entry.videoId());
        return PlaylistQueueRenderer.Menu.newBuilder().setMenuRenderer(
                PlaylistQueueRenderer.MenuRenderer.newBuilder()
                        // Only an activated, currently owned header may attach a batch selection.
                        .addItems(serviceMenuItem(str("playlist_save"), YTIconType.BOOKMARK_BORDER,
                                QueueSaveActions.openPicker(videoIds)))
                        .addItems(serviceMenuItem(str("morphe_native_playlist_queue_clear"), YTIconType.DELETE,
                                PlaylistQueueRenderer.NavigationEndpoint.newBuilder().setHideEnclosingAction(
                                        PlaylistQueueRenderer.HideEnclosingAction.newBuilder().setHack(true)).build()))
                        .setAccessibility(PlaylistQueueRenderer.Accessibility.newBuilder().setAccessibilityData(
                                PlaylistQueueRenderer.AccessibilityLabel.newBuilder()
                                        .setLabel(str("accessibility_overflow_menu")))))
                .build();
    }

    private static PlaylistQueueRenderer.Menu rowMenu(PlaylistQueue source, int index) {
        PlaylistQueue.Entry entry = source.entries().get(index);
        String videoId = entry.videoId();
        var items = PlaylistQueueRenderer.MenuRenderer.newBuilder();
        items.addItems(PlaylistQueueRenderer.MenuItem.newBuilder().setMenuNavigationItemRenderer(
                PlaylistQueueRenderer.MenuNavigationItem.newBuilder()
                        .setText(PlaylistQueueRenderer.Text.newBuilder().setSimpleText(str("accessibility_save_to_playlists")))
                        .setIcon(Icon.newBuilder().setYtIconType(YTIconType.BOOKMARK_BORDER))
                        .setNavigationEndpoint(PlaylistQueueRenderer.NavigationEndpoint.newBuilder()
                                .setAddToPlaylistEndpoint(PlaylistQueueRenderer.AddToPlaylistEndpoint.newBuilder()
                                        .setVideoId(videoId)))));

        var download = PlaylistQueueRenderer.NavigationEndpoint.newBuilder().setOfflineVideoEndpoint(
                PlaylistQueueRenderer.OfflineVideoEndpoint.newBuilder().setVideoId(videoId)
                        .setAction(PlaylistQueueRenderer.OfflineVideoEndpoint.Action.INFER_AUTOMATICALLY)
                        .setOnAdd(PlaylistQueueRenderer.NavigationEndpoint.newBuilder()
                                .setGetDownloadActionCommand(PlaylistQueueRenderer.GetDownloadActionCommand.newBuilder()
                                        // Captured native GetDownloadActionCommand parameters for a row action.
                                        .setVideoId(videoId).setParams("CAIQAA%3D%3D"))));
        items.addItems(serviceMenuItem(str("download"), YTIconType.OFFLINE_DOWNLOAD, download.build()));

        String entity = Base64.encodeToString(
                PlaylistQueueRenderer.ShareEntity.newBuilder().setVideoId(videoId).build().toByteArray(), Base64.NO_WRAP);
        String escapedEntity = Uri.encode(entity);
        String sheetId = "native-playlist-queue-share-" + UUID.randomUUID();
        var request = PlaylistQueueRenderer.NavigationEndpoint.newBuilder().setShareEntityServiceEndpoint(
                PlaylistQueueRenderer.ShareEntityServiceEndpoint.newBuilder()
                        .setSerializedShareEntity(escapedEntity)
                        .setPanelType(PlaylistQueueRenderer.ShareEntityServiceEndpoint.PanelType.ACTION_SHEET)
                        .setSheetId(sheetId));

        // Native onShow runs after the named sheet is created. Its service response
        // populates that sheet; no extension layout or share-target list is needed.
        var share = PlaylistQueueRenderer.NavigationEndpoint.newBuilder().setElementsCommand(
                PlaylistQueueRenderer.ElementsCommand.newBuilder().setShowActionSheetCommand(
                        PlaylistQueueRenderer.ShowActionSheetCommand.newBuilder().setSheetId(sheetId)
                                .setOnShow(PlaylistQueueRenderer.ElementsCommand.newBuilder()
                                        .setInnertubeCommand(request))));
        items.addItems(serviceMenuItem(str("accessibility_share_video"), YTIconType.SHARE, share.build()));

        if (index != source.selectedIndex() && !videoId.equals(VideoInformation.getVideoId())) {
            var remove = PlaylistQueueRenderer.NavigationEndpoint.newBuilder().setHideEnclosingAction(
                    PlaylistQueueRenderer.HideEnclosingAction.newBuilder().setHack(true));
            items.addItems(serviceMenuItem(str("remove"), YTIconType.DELETE, remove.build()));
        }

        return PlaylistQueueRenderer.Menu.newBuilder().setMenuRenderer(items
                .setAccessibility(PlaylistQueueRenderer.Accessibility.newBuilder().setAccessibilityData(
                        PlaylistQueueRenderer.AccessibilityLabel.newBuilder()
                                .setLabel(str("accessibility_overflow_menu")))))
                .build();
    }

    private static PlaylistQueueRenderer.MenuItem serviceMenuItem(
            String label, YTIconType icon, PlaylistQueueRenderer.NavigationEndpoint endpoint) {
        return PlaylistQueueRenderer.MenuItem.newBuilder().setMenuServiceItemRenderer(
                PlaylistQueueRenderer.MenuServiceItem.newBuilder()
                        .setText(PlaylistQueueRenderer.Text.newBuilder().setSimpleText(label))
                        .setIcon(Icon.newBuilder().setYtIconType(icon))
                        .setServiceEndpoint(endpoint)).build();
    }

    private static byte[] renderer(PlaylistQueue source) {
        var panel = PlaylistQueueRenderer.Panel.newBuilder()
                .setTitle(str("morphe_native_playlist_queue_title"))
                .setSelectedIndex(source.selectedIndex()).setPlaylistId("").setVideoCount(source.entries().size())
                .setLocalSelectedIndex(source.selectedIndex())
                .setMenu(playlistMenu(source));
        var swipeToRemove = removalAction();
        for (int index = 0; index < source.entries().size(); index++) {
            PlaylistQueue.Entry entry = source.entries().get(index);
            VideoMetadata metadata = metadataCache.get(entry.videoId());
            String title = metadata == null ? entry.title() : metadata.title();
            var image = PlaylistQueueRenderer.Thumbnail.newBuilder()
                    .setUrl("https://i.ytimg.com/vi/" + entry.videoId() + "/hqdefault.jpg")
                    .setWidth(480).setHeight(360);
            var video = PlaylistQueueRenderer.Video.newBuilder()
                    .setVideoId(entry.videoId()).setSelected(index == source.selectedIndex()).setCanReorder(true)
                    .setMenu(rowMenu(source, index))
                    .setNavigationEndpoint(PlaylistQueueRenderer.NavigationEndpoint.newBuilder()
                            .setWatchEndpoint(PlaylistQueueRenderer.WatchEndpoint.newBuilder().setVideoId(entry.videoId())))
                    .setTitle(PlaylistQueueRenderer.Text.newBuilder().setSimpleText(
                            RestoreOriginalTitlesPatch.getTitle(entry.videoId(), title)))
                    .setMainAppStyle(PlaylistQueueRenderer.MainAppStyle.newBuilder()
                            .setValue(PlaylistQueueRenderer.MainAppStyle.Style.PROMINENT_THUMBNAIL))
                    .setThumbnails(PlaylistQueueRenderer.Thumbnails.newBuilder().addImages(image));
            if (metadata != null) {
                video.setShortBylineText(PlaylistQueueRenderer.Text.newBuilder().setSimpleText(metadata.channelName()))
                        .setVideoInfo(PlaylistQueueRenderer.Text.newBuilder().setSimpleText(metadata.videoInfo()))
                        .setLengthText(PlaylistQueueRenderer.Text.newBuilder().setSimpleText(metadata.duration()));
            }

            if (index != source.selectedIndex()
                    && !entry.videoId().equals(VideoInformation.getVideoId())) {
                video.addSwipeButtons(swipeToRemove);
            }

            QueueRatingActions actions = ratingActions.get(entry.videoId());
            if (index == source.selectedIndex() && actions != null && entry.videoId().equals(VideoInformation.getVideoId())) {
                video.addAllActionButtons(actions.buttons(
                        str("offline_playlist_panel_like_button_accessibility_text"),
                        str("offline_playlist_panel_dislike_button_accessibility_text")));
            }

            panel.addItems(PlaylistQueueRenderer.Item.newBuilder().setVideo(video));
        }

        return panel.build().toByteArray();
    }
}
