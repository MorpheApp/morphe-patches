/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4232
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.downloads;

import static app.morphe.extension.music.patches.downloads.OfflineUi.ICON_BUTTON_DP;
import static app.morphe.extension.music.patches.downloads.OfflineUi.foreground;
import static app.morphe.extension.music.patches.downloads.OfflineUi.secondary;
import static app.morphe.extension.shared.StringRef.str;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.PathShape;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.ui.CustomDialog;

/**
 * Page of the downloads screen for viewing, renaming, reordering and playing one local playlist.
 * <p>
 * A view inside {@link LocalDownloadsFragment} rather than a fragment of its own, so the mini
 * player stays visible below it. The activity recreates the downloads fragment when it is itself
 * recreated, which also makes a fragment back stack unreliable here.
 */
final class LocalPlaylistPage {

    private static final int DRAG_ELEVATION_DP = 8;
    private static final int DRAG_ANIMATION_MILLISECONDS = 100;
    private static final int AUTO_SCROLL_EDGE_DP = 48;
    private static final int AUTO_SCROLL_STEP_DP = 6;
    private static final int DRAG_ICON_DP = 24;

    private final LocalDownloadsFragment host;
    private final Context context;
    private final OfflineUi ui;
    private final OfflinePlaylistManager playlistManager;
    private final String playlistId;

    private final LinearLayout view;
    private final ScrollView scrollView;
    private final LinearLayout tracksContainer;
    private TextView titleView;
    private TextView subtitleView;
    private TextView renameButton;
    private TextView deleteButton;
    private TextView doneButton;

    @Nullable
    private OfflinePlaylist playlist;

    /** In the same order as the rows of {@link #tracksContainer}. */
    private final List<OfflineTrack> tracks = new ArrayList<>();
    private final List<View> removeButtons = new ArrayList<>();
    private final List<View> dragHandles = new ArrayList<>();

    private String playingPath;
    private boolean reorderMode;
    private int draggedIndex = -1;
    private int targetIndex = -1;
    private float dragStartRawY;
    private int dragStartScrollY;

    LocalPlaylistPage(LocalDownloadsFragment host, Context context, OfflineUi ui,
                      OfflinePlaylistManager playlistManager, String playlistId, String playingPath) {
        this.host = host;
        this.context = context;
        this.ui = ui;
        this.playlistManager = playlistManager;
        this.playlistId = playlistId;
        this.playingPath = playingPath;

        view = new LinearLayout(context);
        view.setOrientation(LinearLayout.VERTICAL);
        view.addView(createHeader(), new LinearLayout.LayoutParams(-1, -2));

        scrollView = new ScrollView(context);
        tracksContainer = new LinearLayout(context);
        tracksContainer.setOrientation(LinearLayout.VERTICAL);
        tracksContainer.setPadding(ui.dp(8), ui.dp(4), ui.dp(8), ui.dp(16));
        // A dragged row can be moved past the edges of the list.
        tracksContainer.setClipChildren(false);
        tracksContainer.setClipToPadding(false);
        scrollView.addView(tracksContainer);
        view.addView(scrollView, new LinearLayout.LayoutParams(-1, 0, 1));
    }

    View view() {
        return view;
    }

    /**
     * @return If back navigation was used up by this page. Otherwise, the page should be closed.
     */
    boolean handleBack() {
        if (!reorderMode) return false;
        setReorderMode(false);
        return true;
    }

    private LinearLayout createHeader() {
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(12));

        titleView = ui.text("", 20, foreground());
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setSingleLine(true);
        header.addView(titleView);

        subtitleView = ui.text("", 13, secondary());
        subtitleView.setSingleLine(true);
        header.addView(subtitleView);

        TextView playAllButton = ui.pillButton(str("morphe_music_downloads_playlist_play_all"), true);
        playAllButton.setOnClickListener(v -> playAll());

        renameButton = ui.pillButton(str("morphe_music_downloads_playlist_rename"), false);
        renameButton.setOnClickListener(v -> showRenameDialog());

        deleteButton = ui.pillButton(str("morphe_music_downloads_playlist_delete"), false);
        deleteButton.setOnClickListener(v -> confirmDelete());

        doneButton = ui.pillButton(str("morphe_music_downloads_playlist_reorder_done"), false);
        doneButton.setVisibility(View.GONE);
        doneButton.setOnClickListener(v -> setReorderMode(false));

        LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(-1, -2);
        actionsParams.topMargin = ui.dp(12);
        header.addView(ui.pillBar(playAllButton, renameButton, deleteButton, doneButton), actionsParams);
        return header;
    }

    /** Reloads the playlist from disk and rebuilds the rows, or closes if it no longer exists. */
    void refresh() {
        playlist = playlistManager.getPlaylist(playlistId);
        if (playlist == null) {
            host.closePlaylistPage();
            return;
        }

        tracksContainer.removeAllViews();
        tracks.clear();
        removeButtons.clear();
        dragHandles.clear();

        titleView.setText(playlist.name());
        for (String path : playlist.trackPaths()) {
            OfflineTrack track = OfflineTrack.load(new File(path));
            tracks.add(track);
            tracksContainer.addView(createTrackRow(track));
        }

        if (tracks.isEmpty()) {
            TextView empty = ui.text(str("morphe_music_downloads_playlist_no_tracks"), 15, secondary());
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, ui.dp(60), 0, 0);
            tracksContainer.addView(empty);
            setReorderMode(false);
        }
        updateSubtitle();
        markPlayingRow(playingPath);
    }

    private void updateSubtitle() {
        String count = OfflineUi.trackCount(tracks.size());
        subtitleView.setText(reorderMode
                ? str("morphe_music_downloads_playlist_reorder_hint", count)
                : count);
    }

    private void setReorderMode(boolean enabled) {
        reorderMode = enabled;
        renameButton.setVisibility(enabled ? View.GONE : View.VISIBLE);
        deleteButton.setVisibility(enabled ? View.GONE : View.VISIBLE);
        doneButton.setVisibility(enabled ? View.VISIBLE : View.GONE);
        for (View button : removeButtons) {
            button.setVisibility(enabled ? View.GONE : View.VISIBLE);
        }
        for (View handle : dragHandles) {
            handle.setVisibility(enabled ? View.VISIBLE : View.GONE);
        }
        updateSubtitle();
    }

    /** Lifts the card of the track being played out of the background. */
    void markPlayingRow(String path) {
        playingPath = path;
        for (int i = 0; i < tracks.size(); i++) {
            String trackPath = tracks.get(i).audioFile().getAbsolutePath();
            tracksContainer.getChildAt(i).setBackground(ui.rowBackground(trackPath.equals(path)));
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private View createTrackRow(OfflineTrack track) {
        LinearLayout row = ui.trackRow();
        ui.addTrackInfo(row, track);

        ImageButton removeButton = ui.icon("yt_outline_experimental_trash_can_vd_theme_24",
                str("morphe_music_downloads_playlist_remove_track"));
        removeButton.setVisibility(reorderMode ? View.GONE : View.VISIBLE);
        removeButton.setOnClickListener(v -> removeTrack(track));
        row.addView(removeButton, new LinearLayout.LayoutParams(ui.dp(ICON_BUTTON_DP), ui.dp(ICON_BUTTON_DP)));
        removeButtons.add(removeButton);

        ImageButton dragHandle = ui.icon(createDragHandleDrawable(),
                str("morphe_music_downloads_playlist_drag_track"));
        dragHandle.setVisibility(reorderMode ? View.VISIBLE : View.GONE);
        dragHandle.setOnTouchListener((v, event) -> handleReorderTouch(row, event));
        row.addView(dragHandle, new LinearLayout.LayoutParams(ui.dp(ICON_BUTTON_DP), ui.dp(ICON_BUTTON_DP)));
        dragHandles.add(dragHandle);

        row.setOnClickListener(v -> {
            if (!reorderMode) host.play(track, trackPaths());
        });
        row.setOnLongClickListener(v -> {
            setReorderMode(!reorderMode);
            return true;
        });
        return row;
    }

    /** Three horizontal bars, since the app has no drag handle icon of a usable size. */
    private Drawable createDragHandleDrawable() {
        Path bars = new Path();
        bars.addRect(3, 6, 21, 8, Path.Direction.CW);
        bars.addRect(3, 11, 21, 13, Path.Direction.CW);
        bars.addRect(3, 16, 21, 18, Path.Direction.CW);

        ShapeDrawable handle = new ShapeDrawable(new PathShape(bars, DRAG_ICON_DP, DRAG_ICON_DP));
        handle.setIntrinsicWidth(ui.dp(DRAG_ICON_DP));
        handle.setIntrinsicHeight(ui.dp(DRAG_ICON_DP));
        handle.getPaint().setColor(foreground());
        return handle;
    }

    private boolean handleReorderTouch(View row, MotionEvent event) {
        if (tracks.size() < 2) return false;

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN -> {
                scrollView.requestDisallowInterceptTouchEvent(true);
                dragStartScrollY = scrollView.getScrollY();
                dragStartRawY = event.getRawY();
                draggedIndex = tracksContainer.indexOfChild(row);
                targetIndex = draggedIndex;

                row.setElevation(ui.dp(DRAG_ELEVATION_DP));
                row.setBackground(ui.rowBackground(true));
                return true;
            }
            case MotionEvent.ACTION_MOVE -> {
                autoScrollNearEdges(event.getRawY());

                float deltaY = event.getRawY() - dragStartRawY
                        + scrollView.getScrollY() - dragStartScrollY;
                row.setTranslationY(deltaY);

                int rowHeight = Math.max(1, row.getHeight());
                int offsetRows = Math.round(deltaY / rowHeight);
                targetIndex = Math.max(0, Math.min(tracks.size() - 1, draggedIndex + offsetRows));
                shiftRowsAroundTarget(rowHeight);
                return true;
            }
            case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (draggedIndex < 0) return false;
                finishDrag(row, event.getActionMasked() == MotionEvent.ACTION_UP);
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    private void autoScrollNearEdges(float rawY) {
        int[] location = new int[2];
        scrollView.getLocationOnScreen(location);
        int top = location[1];
        int bottom = top + scrollView.getHeight();
        int edge = ui.dp(AUTO_SCROLL_EDGE_DP);

        if (rawY < top + edge) {
            scrollView.scrollBy(0, -ui.dp(AUTO_SCROLL_STEP_DP));
        } else if (rawY > bottom - edge) {
            scrollView.scrollBy(0, ui.dp(AUTO_SCROLL_STEP_DP));
        }
    }

    /** Moves the rows between the dragged row and its target over, to make room for it. */
    private void shiftRowsAroundTarget(int rowHeight) {
        for (int i = 0; i < tracks.size(); i++) {
            if (i == draggedIndex) continue;

            float translation = 0;
            if (i > draggedIndex && i <= targetIndex) {
                translation = -rowHeight;
            } else if (i < draggedIndex && i >= targetIndex) {
                translation = rowHeight;
            }
            tracksContainer.getChildAt(i).animate().translationY(translation)
                    .setDuration(DRAG_ANIMATION_MILLISECONDS).start();
        }
    }

    private void finishDrag(View row, boolean commit) {
        final int from = draggedIndex;
        final int to = targetIndex;
        // Cleared first, because removing the row below sends it a cancel touch event.
        draggedIndex = -1;
        targetIndex = -1;

        for (int i = 0; i < tracksContainer.getChildCount(); i++) {
            View child = tracksContainer.getChildAt(i);
            child.animate().cancel();
            child.setTranslationY(0);
        }
        row.setElevation(0);

        if (commit && from != to) {
            tracks.add(to, tracks.remove(from));
            tracksContainer.removeView(row);
            tracksContainer.addView(row, to);
            try {
                playlistManager.setTrackOrder(playlistId, trackPaths());
            } catch (IOException ex) {
                Logger.printException(() -> "Could not save the track order", ex);
                Utils.showToastShort(str("morphe_music_downloads_playlist_save_failed"));
            }
        }
        markPlayingRow(playingPath);
    }

    private List<String> trackPaths() {
        List<String> paths = new ArrayList<>();
        for (OfflineTrack track : tracks) {
            paths.add(track.audioFile().getAbsolutePath());
        }
        return paths;
    }

    private void playAll() {
        if (tracks.isEmpty()) {
            Utils.showToastShort(str("morphe_music_downloads_playlist_no_tracks"));
            return;
        }
        host.play(tracks.get(0), trackPaths());
    }

    private void removeTrack(OfflineTrack track) {
        try {
            playlistManager.removeTrack(playlistId, track.audioFile().getAbsolutePath());
            refresh();
        } catch (IOException ex) {
            Logger.printException(() -> "Could not remove track from playlist", ex);
            Utils.showToastShort(str("morphe_music_downloads_playlist_save_failed"));
        }
    }

    private void showRenameDialog() {
        if (playlist == null) return;

        ui.showPlaylistNameDialog(str("morphe_music_downloads_playlist_rename_title"),
                str("morphe_music_downloads_playlist_save"), playlist.name(), name -> {
                    try {
                        playlistManager.renamePlaylist(playlistId, name);
                        refresh();
                    } catch (IOException ex) {
                        Logger.printException(() -> "Could not rename playlist", ex);
                        Utils.showToastShort(str("morphe_music_downloads_playlist_save_failed"));
                    }
                });
    }

    private void confirmDelete() {
        if (playlist == null) return;

        CustomDialog.create(context, str("morphe_music_downloads_playlist_delete_title"),
                str("morphe_music_downloads_playlist_delete_message", playlist.name()), null,
                str("morphe_music_downloads_delete"), () -> {
                    playlistManager.deletePlaylist(playlistId);
                    Utils.showToastShort(str("morphe_music_downloads_playlist_deleted"));
                    host.closePlaylistPage();
                }, () -> {
                }, null, null, false).first.show();
    }
}
