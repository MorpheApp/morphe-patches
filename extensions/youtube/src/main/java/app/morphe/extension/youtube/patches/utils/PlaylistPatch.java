/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches.utils;

import static app.morphe.extension.shared.StringRef.str;
import static app.morphe.extension.shared.Utils.runOnMainThreadDelayed;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.GuardedBy;
import androidx.annotation.NonNull;

import org.apache.commons.collections4.BidiMap;
import org.apache.commons.collections4.bidimap.DualHashBidiMap;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.ui.Dim;
import app.morphe.extension.shared.ui.SheetBottomDialog;
import app.morphe.extension.youtube.innertube.utils.AuthUtils;
import app.morphe.extension.youtube.patches.VideoInformation;
import app.morphe.extension.youtube.patches.utils.requests.CreatePlaylistRequest;
import app.morphe.extension.youtube.patches.utils.requests.EditPlaylistRequest;
import app.morphe.extension.youtube.patches.utils.requests.GetPlaylistItemsRequest;
import app.morphe.extension.youtube.patches.utils.requests.GetPlaylistsRequest;
import app.morphe.extension.youtube.patches.utils.requests.SavePlaylistRequest;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.settings.YouTubeActivityHook;
import app.morphe.extension.youtube.shared.PlayerType;
import kotlin.Pair;

@SuppressWarnings({"unused", "StaticFieldLeak"})
public class PlaylistPatch {
    private static final long DELAY_MILLISECONDS = 1500L;

    private static String initPlaylistId() {
        if (Settings.QUEUE_RESTORE.get()) {
            return Settings.QUEUE_PLAYLIST_ID.get();
        }
        Settings.QUEUE_PLAYLIST_ID.save("");
        return "";
    }

    private static volatile String playlistId = initPlaylistId();
    private static volatile String videoId = "";
    private static volatile boolean syncStarted = false;

    private static String checkFailedAuth = "";
    private static String checkFailedPlaylistId = "";
    private static String checkFailedQueue = "";
    private static String checkFailedVideoId = "";
    private static String checkFailedGeneric = "Failed: %s";

    private static String fetchFailedAdd = "";
    private static String fetchFailedCreate = "";
    private static String fetchFailedRemove = "";
    private static String fetchFailedSave = "";

    private static String fetchSucceededAdd = "";
    private static String fetchSucceededCreate = "";
    private static String fetchSucceededRemove = "";
    private static String fetchSucceededSave = "%s";

    static {
        Context context = Utils.getContext();
        if (context != null && context.getResources() != null) {
            checkFailedAuth = str("morphe_queue_manager_check_failed_auth");
            checkFailedPlaylistId = str("morphe_queue_manager_check_failed_playlist_id");
            checkFailedQueue = str("morphe_queue_manager_check_failed_queue");
            checkFailedVideoId = str("morphe_queue_manager_check_failed_video_id");
            checkFailedGeneric = str("morphe_queue_manager_check_failed_generic");

            fetchFailedAdd = str("morphe_queue_manager_fetch_failed_add");
            fetchFailedCreate = str("morphe_queue_manager_fetch_failed_create");
            fetchFailedRemove = str("morphe_queue_manager_fetch_failed_remove");
            fetchFailedSave = str("morphe_queue_manager_fetch_failed_save");

            fetchSucceededAdd = str("morphe_queue_manager_fetch_succeeded_add");
            fetchSucceededCreate = str("morphe_queue_manager_fetch_succeeded_create");
            fetchSucceededRemove = str("morphe_queue_manager_fetch_succeeded_remove");
            fetchSucceededSave = str("morphe_queue_manager_fetch_succeeded_save");
        }
    }

    @GuardedBy("itself")
    private static final BidiMap<String, String> lastVideoIds = new DualHashBidiMap<>();

    /**
     * Invoked by extension.
     */
    public static void prepareDialogBuilder(@NonNull String currentVideoId) {
        if (AuthUtils.isNotLoggedIn()) {
            handleCheckError(checkFailedAuth);
            return;
        }
        if (currentVideoId.isEmpty()) {
            buildBottomSheetDialog(QueueManager.noVideoIdQueueEntries);
        } else {
            videoId = currentVideoId;
            synchronized (lastVideoIds) {
                QueueManager[] customActionsEntries;
                boolean canReload = PlayerType.getCurrent().isMaximizedOrFullscreen() &&
                        lastVideoIds.get(VideoInformation.getVideoId()) != null;
                if (playlistId.isEmpty() || lastVideoIds.get(currentVideoId) == null) {
                    customActionsEntries = canReload
                            ? QueueManager.addToQueueWithReloadEntries
                            : QueueManager.addToQueueEntries;
                } else {
                    customActionsEntries = canReload
                            ? QueueManager.removeFromQueueWithReloadEntries
                            : QueueManager.removeFromQueueEntries;
                }
                buildBottomSheetDialog(customActionsEntries);
            }
        }
    }

    /**
     * Invoked by extension.
     */
    public static void syncIfNeeded() {
        if (!playlistId.isEmpty() && !syncStarted && !AuthUtils.isNotLoggedIn()) {
            syncStarted = true;
            syncPlaylistItems();
        }
    }

    private static void syncPlaylistItems() {
        Utils.submitOnBackgroundThread(() -> {
            Map<String, String> items = GetPlaylistItemsRequest.fetch(playlistId, AuthUtils.getRequestHeader());
            if (items != null && !items.isEmpty()) {
                synchronized (lastVideoIds) {
                    for (Map.Entry<String, String> entry : items.entrySet()) {
                        lastVideoIds.putIfAbsent(entry.getKey(), entry.getValue());
                    }
                }
                Logger.printDebug(() -> "Synced " + items.size() + " items from queue playlist");
            }
            return null;
        });
    }

    private static void buildBottomSheetDialog(QueueManager[] queueManagerEntries) {
        Context mContext = Utils.getContext();
        if (mContext == null) return;

        SheetBottomDialog.DraggableLinearLayout mainLayout = SheetBottomDialog
                .createMainLayout(mContext, null);

        Map<View, Runnable> actionsMap = new LinkedHashMap<>(2 * queueManagerEntries.length);
        for (QueueManager queueManager : queueManagerEntries) {
            View itemLayout = createItemLayout(mContext, queueManager.label, queueManager.drawableId);
            actionsMap.put(itemLayout, queueManager.onClickAction);
            mainLayout.addView(itemLayout);
        }

        SheetBottomDialog.SlideDialog dialog =
                SheetBottomDialog.createSlideDialog(mContext, mainLayout, 300);

        for (Map.Entry<View, Runnable> entry : actionsMap.entrySet()) {
            Runnable action = entry.getValue();
            entry.getKey().setOnClickListener(v -> {
                dialog.dismiss();
                action.run();
            });
        }

        Utils.runOnMainThread(dialog::show);
    }

    @SuppressLint("ResourceType")
    private static View createItemLayout(Context context, String title, int iconId) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Dim.dp16, Dim.dp16, Dim.dp16, Dim.dp16);
        row.setClickable(true);
        row.setFocusable(true);

        int[] attrs = {android.R.attr.selectableItemBackground};
        Drawable ripple;
        try (TypedArray typedArray = context.obtainStyledAttributes(attrs)) {
            ripple = typedArray.getDrawable(0);
        }
        row.setBackground(ripple);

        ImageView icon = new ImageView(context);
        icon.setImageResource(iconId);
        icon.setColorFilter(Utils.getAppForegroundColor());
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(Dim.dp24, Dim.dp24);
        iconParams.setMarginEnd(Dim.dp16);
        icon.setLayoutParams(iconParams);
        row.addView(icon);

        TextView text = new TextView(context);
        text.setText(title);
        text.setTextColor(Utils.getAppForegroundColor());
        text.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        text.setLayoutParams(textParams);
        row.addView(text);

        return row;
    }

    private static void fetchQueue(boolean remove, boolean openPlaylist,
                                   boolean openVideo, boolean reload) {
        try {
            String currentPlaylistId = playlistId;
            String currentVideoId = videoId;
            synchronized (lastVideoIds) {
                if (currentPlaylistId.isEmpty()) {
                    CreatePlaylistRequest.fetchRequestIfNeeded(currentVideoId, AuthUtils.getRequestHeader());
                    runOnMainThreadDelayed(() -> {
                        CreatePlaylistRequest request = CreatePlaylistRequest.getRequestForVideoId(currentVideoId);
                        if (request != null) {
                            Pair<String, String> playlistIds = request.getPlaylistId();
                            if (playlistIds != null) {
                                String createdPlaylistId = playlistIds.getFirst();
                                String setVideoId = playlistIds.getSecond();
                                if (createdPlaylistId != null && setVideoId != null) {
                                    playlistId = createdPlaylistId;
                                    if (Settings.QUEUE_RESTORE.get()) {
                                        Settings.QUEUE_PLAYLIST_ID.save(createdPlaylistId);
                                    }
                                    lastVideoIds.putIfAbsent(currentVideoId, setVideoId);
                                    showToast(fetchSucceededCreate);
                                    Logger.printDebug(() -> "Queue created, playlistId: " + createdPlaylistId + ", setVideoId: " + setVideoId);
                                    if (openPlaylist) {
                                        openQueue(currentVideoId, openVideo, reload);
                                    }
                                    return;
                                }
                            }
                        }
                        showToast(fetchFailedCreate);
                    }, DELAY_MILLISECONDS);
                } else {
                    String setVideoId = lastVideoIds.get(currentVideoId);
                    EditPlaylistRequest.fetchRequestIfNeeded(currentVideoId, currentPlaylistId, setVideoId, AuthUtils.getRequestHeader());

                    runOnMainThreadDelayed(() -> {
                        EditPlaylistRequest request = EditPlaylistRequest.getRequestForVideoId(currentVideoId);
                        if (request != null) {
                            String fetchedSetVideoId = request.getResult();
                            Logger.printDebug(() -> "fetchedSetVideoId: " + fetchedSetVideoId);
                            if (remove) {
                                if ("".equals(fetchedSetVideoId)) {
                                    lastVideoIds.remove(currentVideoId, setVideoId);
                                    EditPlaylistRequest.clearVideoId(currentVideoId);
                                    showToast(fetchSucceededRemove);
                                    if (openPlaylist) {
                                        openQueue(currentVideoId, openVideo, reload);
                                    }
                                    return;
                                }
                                showToast(fetchFailedRemove);
                            } else {
                                if (fetchedSetVideoId != null && !fetchedSetVideoId.isEmpty()) {
                                    lastVideoIds.putIfAbsent(currentVideoId, fetchedSetVideoId);
                                    EditPlaylistRequest.clearVideoId(currentVideoId);
                                    showToast(fetchSucceededAdd);
                                    Logger.printDebug(() -> "Video added, setVideoId: " + fetchedSetVideoId);
                                    if (openPlaylist) {
                                        openQueue(currentVideoId, openVideo, reload);
                                    }
                                    return;
                                }
                                showToast(fetchFailedAdd);
                            }
                        }
                    }, DELAY_MILLISECONDS);
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "fetchQueue failure", ex);
        }
    }

    private static void saveToPlaylist() {
        String currentPlaylistId = playlistId;
        if (currentPlaylistId.isEmpty()) {
            handleCheckError(checkFailedQueue);
            return;
        }
        try {
            GetPlaylistsRequest.fetchRequestIfNeeded(currentPlaylistId, AuthUtils.getRequestHeader());
            runOnMainThreadDelayed(() -> {
                GetPlaylistsRequest request = GetPlaylistsRequest.getRequestForPlaylistId(currentPlaylistId);
                if (request == null) {
                    return;
                }
                Pair<String, String>[] playlists = request.getPlaylists();
                if (playlists == null) {
                    return;
                }

                Context context = Utils.getContext();
                SheetBottomDialog.DraggableLinearLayout mainLayout = SheetBottomDialog
                        .createMainLayout(context, null);
                Map<View, Runnable> actionsMap = new LinkedHashMap<>(2 * playlists.length);
                int libraryIconId = QueueManager.SAVE_QUEUE.drawableId;

                for (Pair<String, String> playlist : playlists) {
                    String listId = playlist.getFirst();
                    String title = playlist.getSecond();
                    Runnable action = () -> saveToPlaylist(listId, title);
                    View itemLayout = createItemLayout(context, title, libraryIconId);
                    actionsMap.put(itemLayout, action);
                    mainLayout.addView(itemLayout);
                }

                SheetBottomDialog.SlideDialog dialog = SheetBottomDialog
                        .createSlideDialog(context, mainLayout, 300);
                for (Map.Entry<View, Runnable> entry : actionsMap.entrySet()) {
                    Runnable action = entry.getValue();
                    entry.getKey().setOnClickListener(v -> {
                        dialog.dismiss();
                        action.run();
                    });
                }
                dialog.show();
                GetPlaylistsRequest.clear();
            }, DELAY_MILLISECONDS);
        } catch (Exception ex) {
            Logger.printException(() -> "saveToPlaylist failure", ex);
        }
    }

    private static void saveToPlaylist(@Nullable String libraryId, @Nullable String libraryTitle) {
        try {
            if (StringUtils.isEmpty(libraryId)) {
                handleCheckError(checkFailedPlaylistId);
                return;
            }
            SavePlaylistRequest.fetchRequestIfNeeded(playlistId, libraryId, AuthUtils.getRequestHeader());

            runOnMainThreadDelayed(() -> {
                SavePlaylistRequest request = SavePlaylistRequest.getRequestForLibraryId(libraryId);
                if (request == null) {
                    return;
                }

                Boolean result = request.getResult();
                if (Boolean.TRUE.equals(result)) {
                    showToast(String.format(fetchSucceededSave, libraryTitle));
                    SavePlaylistRequest.clear();
                    return;
                }
                showToast(fetchFailedSave);
            }, DELAY_MILLISECONDS);
        } catch (Exception ex) {
            Logger.printException(() -> "saveToPlaylist failure", ex);
        }
    }

    private static void openQueue() {
        openQueue("", false, false);
    }

    private static void openQueue(String currentVideoId, boolean openVideo, boolean reload) {
        String currentPlaylistId = playlistId;
        if (currentPlaylistId.isEmpty()) {
            handleCheckError(checkFailedQueue);
            return;
        }
        try {
            Context context = Utils.getContext();
            if (context == null) return;

            String url;
            if (openVideo) {
                if (StringUtils.isEmpty(currentVideoId)) {
                    handleCheckError(checkFailedVideoId);
                    return;
                }
                if (reload) {
                    url = "https://youtu.be/" + VideoInformation.getVideoId()
                            + "?list=" + currentPlaylistId;
                } else {
                    url = "https://youtu.be/" + currentVideoId + "?list=" + currentPlaylistId;
                }
            } else {
                url = "https://www.youtube.com/playlist?list=" + currentPlaylistId;
            }

            Intent intent = new Intent("android.intent.action.VIEW", Uri.parse(url));
            intent.setPackage(context.getPackageName());
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception ex) {
            Logger.printException(() -> "openQueue failure", ex);
        }
    }

    private static void handleCheckError(String reason) {
        showToast(String.format(checkFailedGeneric, reason));
    }

    private static void showToast(String reason) {
        Utils.showToastShort(reason);
    }

    private enum QueueManager {
        ADD_TO_QUEUE(
                "morphe_queue_manager_add_to_queue",
                "yt_outline_list_add_black_24",
                "yt_outline_experimental_playlist_add_vd_theme_24",
                () -> fetchQueue(false, false, false, false)
        ),
        ADD_TO_QUEUE_AND_OPEN_QUEUE(
                "morphe_queue_manager_add_to_queue_and_open_queue",
                "yt_outline_list_add_black_24",
                "yt_outline_experimental_playlist_add_vd_theme_24",
                () -> fetchQueue(false, true, false, false)
        ),
        ADD_TO_QUEUE_AND_PLAY_VIDEO(
                "morphe_queue_manager_add_to_queue_and_play_video",
                "yt_outline_list_play_arrow_black_24",
                "yt_outline_experimental_playlist_vd_theme_24",
                () -> fetchQueue(false, true, true, false)
        ),
        ADD_TO_QUEUE_AND_RELOAD_VIDEO(
                "morphe_queue_manager_add_to_queue_and_reload_video",
                "yt_outline_arrow_circle_black_24",
                "yt_outline_experimental_replay_vd_theme_24",
                () -> fetchQueue(false, true, true, true)
        ),
        REMOVE_FROM_QUEUE(
                "morphe_queue_manager_remove_from_queue",
                "yt_outline_trash_can_black_24",
                "yt_outline_experimental_circle_slash_vd_theme_24",
                () -> fetchQueue(true, false, false, false)
        ),
        REMOVE_FROM_QUEUE_AND_OPEN_QUEUE(
                "morphe_queue_manager_remove_from_queue_and_open_queue",
                "yt_outline_trash_can_black_24",
                "yt_outline_experimental_circle_slash_vd_theme_24",
                () -> fetchQueue(true, true, false, false)
        ),
        REMOVE_FROM_QUEUE_AND_RELOAD_VIDEO(
                "morphe_queue_manager_remove_from_queue_and_reload_video",
                "yt_outline_arrow_circle_black_24",
                "yt_outline_experimental_replay_vd_theme_24",
                () -> fetchQueue(true, true, true, true)
        ),
        OPEN_QUEUE(
                "morphe_queue_manager_open_queue",
                "yt_outline_list_view_black_24",
                "yt_outline_experimental_queue_vd_theme_24",
                PlaylistPatch::openQueue
        ),
        SAVE_QUEUE(
                "morphe_queue_manager_save_queue",
                "yt_outline_bookmark_black_24",
                "yt_outline_experimental_bookmark_vd_theme_24",
                PlaylistPatch::saveToPlaylist
        );

        public final int drawableId;
        public final String label;
        public final Runnable onClickAction;

        QueueManager(String label, String icon, String boldIcon, Runnable onClickAction) {
            this.drawableId = ResourceUtils.getIdentifier(ResourceType.DRAWABLE,
                    YouTubeActivityHook.USE_BOLD_ICONS ? boldIcon : icon);
            this.label = str(label);
            this.onClickAction = onClickAction;
        }

        public static final QueueManager[] addToQueueEntries = {
                ADD_TO_QUEUE,
                ADD_TO_QUEUE_AND_OPEN_QUEUE,
                ADD_TO_QUEUE_AND_PLAY_VIDEO,
                OPEN_QUEUE,
                SAVE_QUEUE,
        };

        public static final QueueManager[] addToQueueWithReloadEntries = {
                ADD_TO_QUEUE,
                ADD_TO_QUEUE_AND_OPEN_QUEUE,
                ADD_TO_QUEUE_AND_PLAY_VIDEO,
                ADD_TO_QUEUE_AND_RELOAD_VIDEO,
                OPEN_QUEUE,
                SAVE_QUEUE,
        };

        public static final QueueManager[] removeFromQueueEntries = {
                REMOVE_FROM_QUEUE,
                REMOVE_FROM_QUEUE_AND_OPEN_QUEUE,
                OPEN_QUEUE,
                SAVE_QUEUE,
        };

        public static final QueueManager[] removeFromQueueWithReloadEntries = {
                REMOVE_FROM_QUEUE,
                REMOVE_FROM_QUEUE_AND_OPEN_QUEUE,
                REMOVE_FROM_QUEUE_AND_RELOAD_VIDEO,
                OPEN_QUEUE,
                SAVE_QUEUE,
        };

        public static final QueueManager[] noVideoIdQueueEntries = {
                OPEN_QUEUE,
                SAVE_QUEUE,
        };
    }
}
