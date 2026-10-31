/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2528
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.downloads;

import static app.morphe.extension.shared.StringRef.str;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.OvalShape;
import android.os.Bundle;
import android.os.Environment;
import android.preference.PreferenceFragment;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.theme.ThemeUtils;
import app.morphe.extension.shared.ui.CustomDialog;

/** Dedicated screen for viewing, reordering, and playing a local offline playlist. */
@SuppressWarnings("deprecation")
public final class LocalPlaylistFragment extends PreferenceFragment {

    private static final String ARG_PLAYLIST_ID = "arg_playlist_id";
    private static final float SECONDARY_DIM = 0.72f;
    private static final float PLAYING_ROW_BRIGHTNESS = 1.35f;
    private static final int ROW_ARTWORK_PIXELS = 256;
    private static final int ARTWORK_CORNER_DP = 4;
    private static final int ROW_CORNER_DP = 8;
    private static final int PILL_CORNER_DP = 20;
    private static final int ICON_BUTTON_DP = 48;

    private String playlistId;
    private OfflinePlaylistManager playlistManager;
    private OfflinePlaylist playlist;
    private final List<OfflineTrack> playlistTracks = new ArrayList<>();

    // Reorder and drag state variables
    private boolean reorderMode;
    private int draggedIndex = -1;
    private int targetIndex = -1;
    private float dragStartRawY;
    private int dragStartScrollY;

    private TextView titleView;
    private TextView countView;
    private TextView doneReorderBtn;
    private ScrollView scrollView;
    private LinearLayout tracksContainer;
    private Runnable onPlaylistChanged;

    /** Factory method to instantiate the fragment with a specific playlist ID and callback. */
    public static LocalPlaylistFragment newInstance(String playlistId, Runnable onPlaylistChanged) {
        LocalPlaylistFragment fragment = new LocalPlaylistFragment();
        Bundle args = new Bundle();
        args.putString(ARG_PLAYLIST_ID, playlistId);
        fragment.setArguments(args);
        fragment.onPlaylistChanged = onPlaylistChanged;
        return fragment;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle state) {
        File musicRoot = new File(getActivity().getExternalFilesDir(Environment.DIRECTORY_MUSIC), "Morphe");
        playlistManager = new OfflinePlaylistManager(musicRoot);
        playlistId = getArguments() != null ? getArguments().getString(ARG_PLAYLIST_ID, "") : "";

        // Root layout container for the dedicated playlist screen
        LinearLayout root = new LinearLayout(getActivity());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(background());
        root.setClickable(true);
        root.setFocusable(true);

        root.addView(createHeader(), new LinearLayout.LayoutParams(-1, -2));

        // Scrollable container holding the list of tracks inside the playlist
        scrollView = new ScrollView(getActivity());
        tracksContainer = new LinearLayout(getActivity());
        tracksContainer.setOrientation(LinearLayout.VERTICAL);
        tracksContainer.setClipChildren(false);
        tracksContainer.setClipToPadding(false);
        tracksContainer.setPadding(dp(8), dp(4), dp(8), dp(16));
        scrollView.addView(tracksContainer);
        root.addView(scrollView, new LinearLayout.LayoutParams(-1, 0, 1));

        refreshPlaylistView();
        return root;
    }

    /** Creates the top header containing back button, title, track counts, and action buttons. */
    private LinearLayout createHeader() {
        LinearLayout header = new LinearLayout(getActivity());
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(16), dp(12), dp(16), dp(12));

        LinearLayout topRow = new LinearLayout(getActivity());
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        // Back button or exit reorder mode trigger
        TextView backBtn = text("←", 22, foreground());
        backBtn.setPadding(0, dp(4), dp(16), dp(4));
        backBtn.setOnClickListener(v -> {
            if (reorderMode) {
                setReorderMode(false);
                return;
            }
            closeScreen();
        });
        topRow.addView(backBtn, new LinearLayout.LayoutParams(-2, -2));

        LinearLayout titleColumn = new LinearLayout(getActivity());
        titleColumn.setOrientation(LinearLayout.VERTICAL);

        titleView = text("", 20, foreground());
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setSingleLine(true);

        countView = text("", 13, secondary());
        countView.setSingleLine(true);

        titleColumn.addView(titleView);
        titleColumn.addView(countView);
        topRow.addView(titleColumn, new LinearLayout.LayoutParams(0, -2, 1));

        header.addView(topRow, new LinearLayout.LayoutParams(-1, -2));

        // Action buttons row: Play all, Delete playlist, and Done (when reordering)
        LinearLayout actionsRow = new LinearLayout(getActivity());
        actionsRow.setGravity(Gravity.CENTER_VERTICAL);
        actionsRow.setPadding(0, dp(12), 0, 0);

        TextView playAllBtn = actionButton("Play all", true);
        playAllBtn.setOnClickListener(v -> playFromIndex(0));
        actionsRow.addView(playAllBtn, new LinearLayout.LayoutParams(-2, -2));

        View spacer = new View(getActivity());
        actionsRow.addView(spacer, new LinearLayout.LayoutParams(dp(10), dp(1)));

        TextView deleteBtn = actionButton("Delete playlist", false);
        deleteBtn.setOnClickListener(v -> confirmDeletePlaylist());
        actionsRow.addView(deleteBtn, new LinearLayout.LayoutParams(-2, -2));

        View spacer2 = new View(getActivity());
        actionsRow.addView(spacer2, new LinearLayout.LayoutParams(dp(10), dp(1)));

        doneReorderBtn = actionButton("Done", false);
        doneReorderBtn.setVisibility(View.GONE);
        doneReorderBtn.setOnClickListener(v -> setReorderMode(false));
        actionsRow.addView(doneReorderBtn, new LinearLayout.LayoutParams(-2, -2));

        header.addView(actionsRow, new LinearLayout.LayoutParams(-1, -2));
        return header;
    }

    /** Enables or disables track reordering mode across the playlist. */
    private void setReorderMode(boolean enabled) {
        if (this.reorderMode == enabled) return;
        this.reorderMode = enabled;
        if (doneReorderBtn != null) {
            doneReorderBtn.setVisibility(enabled ? View.VISIBLE : View.GONE);
        }
        updateSubtitleText();
        refreshActionButtonsState();
    }

    /** Updates the subtitle text to guide the user when reorder mode is active. */
    private void updateSubtitleText() {
        if (countView == null) return;
        String baseCount = playlistTracks.size() + (playlistTracks.size() == 1 ? " track" : " tracks");
        countView.setText(reorderMode ? baseCount + " • Drag handle to reorder" : baseCount);
    }

    /** Switches between trash/remove buttons and drag handles on all rows based on reorder state. */
    private void refreshActionButtonsState() {
        for (int i = 0; i < tracksContainer.getChildCount(); i++) {
            View child = tracksContainer.getChildAt(i);
            if (!(child instanceof LinearLayout row)) continue;
            View removeBtn = row.findViewWithTag("remove_btn");
            View reorderBtn = row.findViewWithTag("reorder_btn");
            if (removeBtn != null) removeBtn.setVisibility(reorderMode ? View.GONE : View.VISIBLE);
            if (reorderBtn != null) reorderBtn.setVisibility(reorderMode ? View.VISIBLE : View.GONE);
        }
    }

    /** Helper to create styled pill buttons with ripple feedback. */
    private TextView actionButton(String label, boolean primary) {
        final int fg = foreground();
        TextView btn = text(label, 14, primary ? background() : fg);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        btn.setPadding(dp(18), dp(8), dp(18), dp(8));

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(PILL_CORNER_DP));
        if (primary) {
            bg.setColor(fg);
        } else {
            bg.setColor(Color.argb(26, Color.red(fg), Color.green(fg), Color.blue(fg)));
            bg.setStroke(dp(1), Color.argb(65, Color.red(fg), Color.green(fg), Color.blue(fg)));
        }
        btn.setBackground(new RippleDrawable(ColorStateList.valueOf(rippleColor()), bg,
                CustomDialog.createRoundedBackground(PILL_CORNER_DP, Color.WHITE)));
        return btn;
    }

    /** Reloads playlist tracks from disk and rebuilds the song list UI. */
    private void refreshPlaylistView() {
        playlist = playlistManager.getPlaylist(playlistId);
        tracksContainer.removeAllViews();
        playlistTracks.clear();

        if (playlist == null) {
            closeScreen();
            return;
        }

        for (String path : playlist.trackPaths()) {
            File file = new File(path);
            if (file.isFile()) {
                playlistTracks.add(OfflineTrack.load(file));
            }
        }

        titleView.setText(playlist.name());
        updateSubtitleText();

        if (playlistTracks.isEmpty()) {
            setReorderMode(false);
            TextView empty = text("No tracks in this playlist", 15, secondary());
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(60), 0, 0);
            tracksContainer.addView(empty);
            return;
        }

        for (int i = 0; i < playlistTracks.size(); i++) {
            OfflineTrack track = playlistTracks.get(i);
            tracksContainer.addView(createTrackRow(track, i));
        }
    }

    /** Constructs an individual track row view with artwork, metadata, remove action, and reorder handle. */
    @SuppressLint("ClickableViewAccessibility")
    private View createTrackRow(OfflineTrack track, int index) {
        LinearLayout row = new LinearLayout(getActivity());
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(7), dp(8), dp(7));
        row.setBackground(rowBackground(false));

        row.addView(artworkView(track.artwork(ROW_ARTWORK_PIXELS)),
                new LinearLayout.LayoutParams(dp(56), dp(56)));

        LinearLayout labels = new LinearLayout(getActivity());
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(dp(14), 0, dp(8), 0);

        TextView name = text(track.displayTitle(), 16, foreground());
        name.setSingleLine(true);

        TextView detail = text(str("morphe_music_downloads_track_subtitle",
                track.displayArtist(), formatDuration(track.durationSeconds()),
                formatSize(track.audioFile().length())), 13, secondary());
        detail.setSingleLine(true);

        labels.addView(name);
        labels.addView(detail);
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));

        // Delete/Remove button (visible in normal mode)
        ImageButton removeBtn = icon("yt_outline_experimental_trash_can_vd_theme_24", "Remove from playlist");
        removeBtn.setTag("remove_btn");
        removeBtn.setVisibility(reorderMode ? View.GONE : View.VISIBLE);
        removeBtn.setOnClickListener(v -> removeTrackFromPlaylist(track));
        row.addView(removeBtn, new LinearLayout.LayoutParams(dp(ICON_BUTTON_DP), dp(ICON_BUTTON_DP)));

        // Reorder drag handle button (visible in reorder mode)
        ImageButton reorderBtn = new ImageButton(getActivity());
        reorderBtn.setTag("reorder_btn");
        reorderBtn.setImageDrawable(createDragHandleDrawable());
        reorderBtn.setContentDescription("Drag to reorder");
        reorderBtn.setScaleType(ImageView.ScaleType.CENTER);
        reorderBtn.setPadding(dp(10), dp(10), dp(10), dp(10));
        reorderBtn.setBackground(new RippleDrawable(ColorStateList.valueOf(rippleColor()),
                null, new ShapeDrawable(new OvalShape())));
        reorderBtn.setVisibility(reorderMode ? View.VISIBLE : View.GONE);
        reorderBtn.setOnTouchListener((v, event) -> handleReorderTouch(row, index, event));
        row.addView(reorderBtn, new LinearLayout.LayoutParams(dp(ICON_BUTTON_DP), dp(ICON_BUTTON_DP)));

        // Tapping a row plays the track unless reordering is active
        row.setOnClickListener(v -> {
            if (reorderMode) return;
            playFromIndex(index);
        });

        // Long-pressing any song row toggles reorder mode instantly
        row.setOnLongClickListener(v -> {
            setReorderMode(!reorderMode);
            return true;
        });

        return row;
    }

    /** Handles touch events for drag-and-drop reordering of playlist items. */
    private boolean handleReorderTouch(View draggedRow, int startIndex, MotionEvent event) {
        if (!reorderMode || playlistTracks.size() <= 1) return false;

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN -> {
                if (scrollView != null) {
                    scrollView.requestDisallowInterceptTouchEvent(true);
                    dragStartScrollY = scrollView.getScrollY();
                }
                draggedIndex = startIndex;
                targetIndex = startIndex;
                dragStartRawY = event.getRawY();

                draggedRow.setElevation(dp(8));
                draggedRow.setBackground(rowBackground(true));
                return true;
            }
            case MotionEvent.ACTION_MOVE -> {
                if (draggedIndex < 0) return false;

                // Auto-scroll ScrollView if dragging near top or bottom edges
                if (scrollView != null) {
                    int[] loc = new int[2];
                    scrollView.getLocationOnScreen(loc);
                    int scrollTop = loc[1];
                    int scrollBottom = scrollTop + scrollView.getHeight();
                    int edgeZone = dp(48);

                    if (event.getRawY() < scrollTop + edgeZone) {
                        scrollView.scrollBy(0, -dp(6));
                    } else if (event.getRawY() > scrollBottom - edgeZone) {
                        scrollView.scrollBy(0, dp(6));
                    }
                }

                int currentScrollY = scrollView != null ? scrollView.getScrollY() : 0;
                float deltaY = (event.getRawY() - dragStartRawY) + (currentScrollY - dragStartScrollY);
                draggedRow.setTranslationY(deltaY);

                int rowHeight = Math.max(1, draggedRow.getHeight());
                int offsetSteps = Math.round(deltaY / rowHeight);
                targetIndex = Math.max(0, Math.min(playlistTracks.size() - 1, draggedIndex + offsetSteps));

                // Animate neighboring rows to indicate position change
                for (int i = 0; i < tracksContainer.getChildCount(); i++) {
                    if (i == draggedIndex) continue;
                    View child = tracksContainer.getChildAt(i);
                    float targetTranslation = 0f;
                    if (i > draggedIndex && i <= targetIndex) {
                        targetTranslation = -rowHeight;
                    } else if (i < draggedIndex && i >= targetIndex) {
                        targetTranslation = rowHeight;
                    }
                    child.animate().translationY(targetTranslation).setDuration(100).start();
                }
                return true;
            }
            case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (draggedIndex < 0) return false;

                int from = draggedIndex;
                int to = targetIndex;
                draggedIndex = -1;
                targetIndex = -1;

                // Reset elevations and translations
                for (int i = 0; i < tracksContainer.getChildCount(); i++) {
                    View child = tracksContainer.getChildAt(i);
                    child.animate().cancel();
                    child.setTranslationY(0f);
                    child.setElevation(0f);
                }
                draggedRow.setBackground(rowBackground(false));

                // Commit reorder changes if position changed
                if (from != to && to >= 0 && to < playlistTracks.size()) {
                    OfflineTrack moved = playlistTracks.remove(from);
                    playlistTracks.add(to, moved);
                    saveCurrentTrackOrder();
                }
                refreshPlaylistView();
                return true;
            }
        }
        return false;
    }

    /** Saves the newly reordered track path sequence to disk via OfflinePlaylistManager. */
    private void saveCurrentTrackOrder() {
        List<String> orderedPaths = new ArrayList<>();
        for (OfflineTrack track : playlistTracks) {
            orderedPaths.add(track.audioFile().getAbsolutePath());
        }
        try {
            playlistManager.updateTrackOrder(playlistId, orderedPaths);
            if (onPlaylistChanged != null) onPlaylistChanged.run();
        } catch (Exception ex) {
            Logger.printException(() -> "Failed to save reordered playlist", ex);
            Utils.showToastShort("Could not save track order");
        }
    }

    /** Creates the row background drawable with optional highlighting for active/dragging states. */
    private Drawable rowBackground(boolean highlighted) {
        Drawable card = highlighted
                ? CustomDialog.createRoundedBackground(ROW_CORNER_DP, Utils.adjustColorBrightness(
                        ThemeUtils.getAppBackgroundColor(), PLAYING_ROW_BRIGHTNESS))
                : null;
        return new RippleDrawable(ColorStateList.valueOf(rippleColor()), card,
                CustomDialog.createRoundedBackground(ROW_CORNER_DP, Color.WHITE));
    }

    /** Generates a custom 3-bar drag handle icon (≡) matching the app foreground color. */
    private Drawable createDragHandleDrawable() {
        final int size = dp(24);
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(foreground());
        paint.setStrokeWidth(dp(2));
        paint.setStrokeCap(Paint.Cap.ROUND);

        return new Drawable() {
            @Override
            public void draw(@NonNull Canvas canvas) {
                float left = getBounds().left + dp(3);
                float right = getBounds().right - dp(3);
                float centerY = getBounds().exactCenterY();
                float gap = dp(5);

                canvas.drawLine(left, centerY - gap, right, centerY - gap, paint);
                canvas.drawLine(left, centerY, right, centerY, paint);
                canvas.drawLine(left, centerY + gap, right, centerY + gap, paint);
            }

            @Override
            public int getIntrinsicWidth() {
                return size;
            }

            @Override
            public int getIntrinsicHeight() {
                return size;
            }

            @Override
            public void setAlpha(int alpha) {
                paint.setAlpha(alpha);
            }

            @Override
            public void setColorFilter(@Nullable ColorFilter colorFilter) {
                paint.setColorFilter(colorFilter);
            }

            @Override
            public int getOpacity() {
                return PixelFormat.TRANSLUCENT;
            }
        };
    }

    /** Removes a specific track path from the playlist metadata without deleting the underlying audio file. */
    private void removeTrackFromPlaylist(OfflineTrack track) {
        try {
            playlistManager.removeTrackPathFromPlaylist(playlistId, track.audioFile().getAbsolutePath());
            if (onPlaylistChanged != null) onPlaylistChanged.run();
            refreshPlaylistView();
            Utils.showToastShort("Removed from playlist");
        } catch (Exception ex) {
            Utils.showToastShort("Could not remove track");
        }
    }

    /** Prompts the user for confirmation before deleting the playlist metadata file. */
    private void confirmDeletePlaylist() {
        String name = playlist != null ? playlist.name() : "";
        CustomDialog.create(
                getActivity(),
                "Delete playlist?",
                "Delete \"" + name + "\"? Downloaded songs will not be deleted.",
                null,
                str("morphe_music_downloads_delete"),
                () -> {
                    playlistManager.deletePlaylist(playlistId);
                    if (onPlaylistChanged != null) onPlaylistChanged.run();
                    Utils.showToastShort("Playlist deleted");
                    closeScreen();
                },
                () -> {},
                null,
                null,
                false
        ).first.show();
    }

    /** Starts playing the playlist queue starting from the specified index. */
    private void playFromIndex(int startIndex) {
        if (playlistTracks.isEmpty() || startIndex < 0 || startIndex >= playlistTracks.size()) {
            Utils.showToastShort("No local tracks available in this playlist");
            return;
        }

        ArrayList<String> queue = new ArrayList<>();
        for (OfflineTrack t : playlistTracks) {
            queue.add(t.audioFile().getAbsolutePath());
        }

        // Sync queue with LocalDownloadsFragment so mini-player skip buttons work correctly
        LocalDownloadsFragment.setActiveQueue(queue);

        OfflineTrack target = playlistTracks.get(startIndex);
        try {
            Intent intent = new Intent(getActivity(), OfflinePlaybackService.class)
                    .setAction(OfflinePlaybackService.ACTION_PLAY_FILE)
                    .putExtra(OfflinePlaybackService.EXTRA_PATH, target.audioFile().getAbsolutePath())
                    .putExtra(OfflinePlaybackService.EXTRA_TITLE, target.displayTitle())
                    .putExtra(OfflinePlaybackService.EXTRA_ARTIST, target.displayArtist())
                    .putExtra(OfflinePlaybackService.EXTRA_ARTWORK_PATH, target.artworkFile().getAbsolutePath())
                    .putStringArrayListExtra(OfflinePlaybackService.EXTRA_QUEUE, queue)
                    .putExtra(OfflinePlaybackService.EXTRA_QUEUE_INDEX, startIndex);
            getActivity().startForegroundService(intent);
        } catch (Exception ex) {
            Logger.printException(() -> "Playlist playback failed", ex);
            Utils.showToastShort(str("morphe_music_downloads_play_failed"));
        }
    }

    /** Pops this fragment off the back stack to return to the downloads screen. */
    private void closeScreen() {
        if (getFragmentManager() != null) {
            getFragmentManager().popBackStack();
        }
    }

    /** Generates an artwork view with rounded corners and placeholder fallback. */
    private ImageView artworkView(@Nullable Bitmap bitmap) {
        ImageView view = new ImageView(getActivity());
        view.setBackgroundColor(Utils.adjustColorBrightness(foreground(), 0.2f));
        final float radius = dp(ARTWORK_CORNER_DP);
        view.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View outlined, Outline outline) {
                outline.setRoundRect(0, 0, outlined.getWidth(), outlined.getHeight(), radius);
            }
        });
        view.setClipToOutline(true);

        if (bitmap != null) {
            view.setImageBitmap(bitmap);
            view.setScaleType(ImageView.ScaleType.CENTER_CROP);
        } else {
            view.setImageDrawable(ResourceUtils.getDrawable("yt_fill_experimental_play_circle_vd_theme_24"));
            view.setColorFilter(foreground());
            view.setPadding(dp(16), dp(16), dp(16), dp(16));
        }
        return view;
    }

    /** Creates a standard square icon button with ripple click feedback. */
    private ImageButton icon(String drawable, String description) {
        ImageButton button = new ImageButton(getActivity());
        button.setImageDrawable(ResourceUtils.getDrawable(drawable));
        button.setColorFilter(foreground());
        button.setContentDescription(description);
        button.setScaleType(ImageView.ScaleType.CENTER);
        button.setPadding(dp(10), dp(10), dp(10), dp(10));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(rippleColor()),
                null, new ShapeDrawable(new OvalShape())));
        return button;
    }

    /** Utility helper to construct styled text views. */
    private TextView text(String value, int sp, int color) {
        TextView view = new TextView(getActivity());
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private static int foreground() {
        return ThemeUtils.getAppForegroundColor();
    }

    private static int secondary() {
        return Utils.adjustColorBrightness(foreground(), SECONDARY_DIM);
    }

    private static int background() {
        return ThemeUtils.getAppBackgroundColor();
    }

    private static int rippleColor() {
        final int fg = foreground();
        return Color.argb(60, Color.red(fg), Color.green(fg), Color.blue(fg));
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + .5f);
    }

    private static String formatDuration(int seconds) {
        if (seconds <= 0) return "";
        if (seconds < 3600) {
            return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
        }
        return String.format(Locale.ROOT, "%d:%02d:%02d",
                seconds / 3600, (seconds % 3600) / 60, seconds % 60);
    }

    private static String formatSize(long bytes) {
        return String.format(Locale.ROOT, "%.1f", bytes / 1048576.0);
    }
}
