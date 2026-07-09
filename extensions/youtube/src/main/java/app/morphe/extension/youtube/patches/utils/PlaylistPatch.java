/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches.utils;

import static app.morphe.extension.shared.StringRef.str;
import static app.morphe.extension.shared.innertube.utils.AuthUtils.getRequestHeader;
import static app.morphe.extension.shared.innertube.utils.AuthUtils.isNotLoggedIn;
import static app.morphe.extension.youtube.patches.LoadVideoPatch.openIntent;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.GuardedBy;

import org.apache.commons.collections4.BidiMap;
import org.apache.commons.collections4.bidimap.DualHashBidiMap;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.ui.Dim;
import app.morphe.extension.shared.ui.SheetBottomDialog;
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
    private static final String checkFailedAuth = str("morphe_queue_manager_check_failed_auth");
    private static final String checkFailedPlaylistId = str("morphe_queue_manager_check_failed_playlist_id");
    private static final String checkFailedQueue = str("morphe_queue_manager_check_failed_queue");
    private static final String checkFailedVideoId = str("morphe_queue_manager_check_failed_video_id");
    private static final String checkFailedGeneric = str("morphe_queue_manager_check_failed_generic");

    private static final String fetchFailedAdd = str("morphe_queue_manager_fetch_failed_add");
    private static final String fetchFailedCreate = str("morphe_queue_manager_fetch_failed_create");
    private static final String fetchFailedRemove = str("morphe_queue_manager_fetch_failed_remove");
    private static final String fetchFailedSave = str("morphe_queue_manager_fetch_failed_save");

    private static final String fetchSucceededAdd = str("morphe_queue_manager_fetch_succeeded_add");
    private static final String fetchSucceededCreate = str("morphe_queue_manager_fetch_succeeded_create");
    private static final String fetchSucceededRemove = str("morphe_queue_manager_fetch_succeeded_remove");
    private static final String fetchSucceededSave = str("morphe_queue_manager_fetch_succeeded_save");

    private static volatile String playlistId = Settings.QUEUE_RESTORE.get()
            ? Settings.QUEUE_PLAYLIST_ID.get()
            : Settings.QUEUE_PLAYLIST_ID.resetToDefault();
    private static volatile String videoId = "";
    private static volatile boolean syncStarted;

    @GuardedBy("itself")
    private static final BidiMap<String, String> lastVideoIds = new DualHashBidiMap<>();

    /**
     * Invoked by extension.
     */
    public static void prepareDialogBuilder(Context context, String currentVideoId) {
        Utils.verifyOnMainThread();

        if (isNotLoggedIn()) {
            handleCheckError(checkFailedAuth);
            return;
        }
        if (currentVideoId.isEmpty()) {
            buildBottomSheetDialog(context, QueueManager.noVideoIdQueueEntries);
        } else {
            synchronized (lastVideoIds) {
                videoId = currentVideoId;
                QueueManager[] customActionsEntries;
                boolean canReload = !PlayerType.getCurrent().isNoneOrHidden() &&
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
                buildBottomSheetDialog(context, customActionsEntries);
            }
        }
    }

    /**
     * Invoked by extension.
     */
    public static void syncIfNeeded() {
        if (!playlistId.isEmpty() && !syncStarted && !isNotLoggedIn()) {
            syncStarted = true;
            syncPlaylistItems();
        }
    }

    private static void syncPlaylistItems() {
        Utils.submitOnBackgroundThread(() -> {
            Map<String, String> items = GetPlaylistItemsRequest.fetch(playlistId, getRequestHeader());
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

    private static void buildBottomSheetDialog(Context context, QueueManager[] queueManagerEntries) {
        SheetBottomDialog.DraggableLinearLayout mainLayout = SheetBottomDialog
                .createMainLayout(context, null);

        Map<View, Function<Context, Void>> actionsMap = new LinkedHashMap<>(2 * queueManagerEntries.length);
        for (QueueManager queueManager : queueManagerEntries) {
            View itemLayout = createItemLayout(context, queueManager.label, queueManager.drawableId);
            actionsMap.put(itemLayout, queueManager.onClickAction);
            mainLayout.addView(itemLayout);
        }

        SheetBottomDialog.SlideDialog dialog = SheetBottomDialog
                .createSlideDialog(context, mainLayout, 300);

        for (Map.Entry<View, Function<Context, Void>> entry : actionsMap.entrySet()) {
            Function<Context, Void> action = entry.getValue();
            entry.getKey().setOnClickListener(v -> {
                dialog.dismiss();
                action.apply(context);
            });
        }

        dialog.show();
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

    private static void fetchQueue(Context context, boolean remove, boolean openPlaylist,
                                   boolean openVideo, boolean reload, boolean retry) {
        String currentPlaylistId = playlistId;
        String currentVideoId = videoId;
        Utils.runOnBackgroundThread(() -> {
            synchronized (lastVideoIds) {
                if (currentPlaylistId.isEmpty()) {
                    CreatePlaylistRequest.fetchRequestIfNeeded(currentVideoId, getRequestHeader());
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
                                Logger.printDebug(() -> "Queue created, playlistId: "
                                        + createdPlaylistId + ", setVideoId: " + setVideoId);
                                if (openPlaylist) {
                                    openQueue(context, currentVideoId, openVideo, reload);
                                }
                                return;
                            }
                        }
                    }
                    showToast(fetchFailedCreate);
                } else {
                    String setVideoId = lastVideoIds.get(currentVideoId);
                    EditPlaylistRequest.fetchRequestIfNeeded(currentVideoId, currentPlaylistId,
                            setVideoId, getRequestHeader());
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
                                    openQueue(context, currentVideoId, openVideo, reload);
                                }
                                return;
                            }
                            showToast(fetchFailedRemove);
                        } else {
                            if (fetchedSetVideoId == null || fetchedSetVideoId.isEmpty()) {
                                Logger.printDebug(() -> "Playlist not available fetchedSetVideoId: "
                                        + fetchedSetVideoId);
                                if (!retry) {
                                    // Already retried, give up.
                                    showToast(fetchFailedAdd);
                                    return;
                                }
                                // Clear saved playlist and try again.
                                playlistId = Settings.QUEUE_PLAYLIST_ID.resetToDefault();
                                fetchQueue(context, false, openPlaylist, openVideo, reload, false);
                                return;
                            }

                            lastVideoIds.putIfAbsent(currentVideoId, fetchedSetVideoId);
                            EditPlaylistRequest.clearVideoId(currentVideoId);
                            Logger.printDebug(() -> "Video added, setVideoId: " + fetchedSetVideoId);
                            showToast(fetchSucceededAdd);
                            if (openPlaylist) {
                                openQueue(context, currentVideoId, openVideo, reload);
                            }
                        }
                    }
                }
            }
        });
    }

    private static void saveToPlaylist(Context context) {
        String currentPlaylistId = playlistId;
        if (currentPlaylistId.isEmpty()) {
            handleCheckError(checkFailedQueue);
            return;
        }
        try {
            GetPlaylistsRequest request = GetPlaylistsRequest.fetchRequestIfNeeded(
                    currentPlaylistId, getRequestHeader());
            if (request == null) {
                return;
            }
            Utils.runOnBackgroundThread(() ->  {
                Pair<String, String>[] playlists = request.getPlaylists();
                if (playlists == null || playlists.length == 0) return;
                Utils.runOnMainThread(() -> {
                    SheetBottomDialog.DraggableLinearLayout mainLayout = SheetBottomDialog
                            .createMainLayout(context, null);
                    Map<View, Runnable> actionsMap = new LinkedHashMap<>(2 * playlists.length);
                    int libraryIconId = QueueManager.SAVE_QUEUE.drawableId;

                    LinearLayout listContainer = new LinearLayout(context);
                    listContainer.setOrientation(LinearLayout.VERTICAL);

                    for (Pair<String, String> playlist : playlists) {
                        String listId = playlist.getFirst();
                        String title = playlist.getSecond();
                        Runnable action = () -> saveToPlaylist(listId, title);
                        View itemLayout = createItemLayout(context, title, libraryIconId);
                        actionsMap.put(itemLayout, action);
                        listContainer.addView(itemLayout);
                    }

                    ScrollView scrollView = new ScrollView(context) {
                        @Override
                        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                            heightMeasureSpec = MeasureSpec.makeMeasureSpec(Dim.pctHeight(50), MeasureSpec.AT_MOST);
                            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
                        }
                    };
                    scrollView.setVerticalScrollBarEnabled(false);
                    scrollView.addView(listContainer);
                    mainLayout.addView(scrollView);

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
                });
            });
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
            SavePlaylistRequest request = SavePlaylistRequest.fetchRequestIfNeeded(
                    playlistId, libraryId, getRequestHeader());
            if (request == null) {
                return;
            }
            Utils.runOnBackgroundThread(() -> {
                Boolean result = request.getResult();
                if (Boolean.TRUE.equals(result)) {
                    showToast(String.format(fetchSucceededSave, libraryTitle));
                    SavePlaylistRequest.clear();
                    return;
                }
                showToast(fetchFailedSave);
            });
        } catch (Exception ex) {
            Logger.printException(() -> "saveToPlaylist failure", ex);
        }
    }

    private static void openQueue(Context context) {
        openQueue(context, "", false, false);
    }

    private static void openQueue(Context context, String currentVideoId, boolean openVideo, boolean reload) {
        Utils.runOnMainThreadNowOrLater(() -> {
            String currentPlaylistId = playlistId;
            if (currentPlaylistId.isEmpty()) {
                handleCheckError(checkFailedQueue);
                return;
            }
            try {
                String url;
                if (openVideo) {
                    if (StringUtils.isEmpty(currentVideoId)) {
                        handleCheckError(checkFailedVideoId);
                        return;
                    }
                    if (reload) {
                        final long videoTime = VideoInformation.getVideoTime();

                        url = "https://www.youtube.com/watch?v=" +
                                VideoInformation.getVideoId() +
                                "&list=" +
                                currentPlaylistId +
                                (videoTime > 0 ? "&t=" + (videoTime / 1000) : "");
                    } else {
                        url = "https://www.youtube.com/watch?v=" +
                                currentVideoId +
                                "&list=" +
                                currentPlaylistId;
                    }
                } else {
                    url = "https://www.youtube.com/playlist?list=" +
                            currentPlaylistId;
                }

                openIntent(url, reload);
            } catch (Exception ex) {
                Logger.printException(() -> "openQueue failure", ex);
            }
        });
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
                context -> {
                    fetchQueue(context, false, false, false, false, true);
                    return null;
                }
        ),
        ADD_TO_QUEUE_AND_OPEN_QUEUE(
                "morphe_queue_manager_add_to_queue_and_open_queue",
                "yt_outline_list_add_black_24",
                "yt_outline_experimental_playlist_add_vd_theme_24",
                context -> {
                    fetchQueue(context, false, true, false, false, true);
                    return null;
                }
        ),
        ADD_TO_QUEUE_AND_PLAY_VIDEO(
                "morphe_queue_manager_add_to_queue_and_play_video",
                "yt_outline_list_play_arrow_black_24",
                "yt_outline_experimental_playlist_vd_theme_24",
                context -> {
                    fetchQueue(context, false, true, true, false, true);
                    return null;
                }
        ),
        ADD_TO_QUEUE_AND_RELOAD_VIDEO(
                "morphe_queue_manager_add_to_queue_and_reload_video",
                "yt_outline_arrow_circle_black_24",
                "yt_outline_experimental_replay_vd_theme_24",
                context -> {
                    fetchQueue(context, false, true, true, true, true);
                    return null;
                }
        ),
        REMOVE_FROM_QUEUE(
                "morphe_queue_manager_remove_from_queue",
                "yt_outline_trash_can_black_24",
                "yt_outline_experimental_circle_slash_vd_theme_24",
                context -> {
                    fetchQueue(context, true, false, false, false, true);
                    return null;
                }
        ),
        REMOVE_FROM_QUEUE_AND_OPEN_QUEUE(
                "morphe_queue_manager_remove_from_queue_and_open_queue",
                "yt_outline_trash_can_black_24",
                "yt_outline_experimental_circle_slash_vd_theme_24",
                context -> {
                    fetchQueue(context, true, true, false, false, true);
                    return null;
                }
        ),
        REMOVE_FROM_QUEUE_AND_RELOAD_VIDEO(
                "morphe_queue_manager_remove_from_queue_and_reload_video",
                "yt_outline_arrow_circle_black_24",
                "yt_outline_experimental_replay_vd_theme_24",
                context -> {
                    fetchQueue(context, true, true, true, true, true);
                    return null;
                }
        ),
        OPEN_QUEUE(
                "morphe_queue_manager_open_queue",
                "yt_outline_list_view_black_24",
                "yt_outline_experimental_queue_vd_theme_24",
                context -> {
                    PlaylistPatch.openQueue(context);
                    return null;
                }
        ),
        SAVE_QUEUE(
                "morphe_queue_manager_save_queue",
                "yt_outline_bookmark_black_24",
                "yt_outline_experimental_bookmark_vd_theme_24",
                context -> {
                    PlaylistPatch.saveToPlaylist(context);
                    return null;
                }
        );

        public final int drawableId;
        public final String label;

        public final Function<Context, Void> onClickAction;

        QueueManager(String label, String icon, String boldIcon, Function<Context, Void> onClickAction) {
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
