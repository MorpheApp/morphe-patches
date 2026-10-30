package app.morphe.extension.music.patches.downloads;

import static app.morphe.extension.shared.StringRef.str;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Outline;
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
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

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

/** Dedicated screen for viewing and playing a local offline playlist. */
@SuppressWarnings("deprecation")
public final class LocalPlaylistFragment extends PreferenceFragment {

    private static final String ARG_PLAYLIST_ID = "arg_playlist_id";
    private static final float SECONDARY_DIM = 0.72f;
    private static final int ROW_ARTWORK_PIXELS = 256;
    private static final int ARTWORK_CORNER_DP = 4;
    private static final int ROW_CORNER_DP = 8;
    private static final int ICON_BUTTON_DP = 48;

    private String playlistId;
    private OfflinePlaylistManager playlistManager;
    private OfflinePlaylist playlist;
    private final List<OfflineTrack> playlistTracks = new ArrayList<>();

    private TextView titleView;
    private TextView countView;
    private LinearLayout tracksContainer;
    private Runnable onPlaylistChanged;

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

        LinearLayout root = new LinearLayout(getActivity());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(background());
        root.setClickable(true);
        root.setFocusable(true);

        root.addView(createHeader(), new LinearLayout.LayoutParams(-1, -2));

        ScrollView scroll = new ScrollView(getActivity());
        tracksContainer = new LinearLayout(getActivity());
        tracksContainer.setOrientation(LinearLayout.VERTICAL);
        tracksContainer.setPadding(dp(8), dp(4), dp(8), dp(16));
        scroll.addView(tracksContainer);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        refreshPlaylistView();
        return root;
    }

    private LinearLayout createHeader() {
        LinearLayout header = new LinearLayout(getActivity());
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(16), dp(12), dp(16), dp(12));

        LinearLayout topRow = new LinearLayout(getActivity());
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView backBtn = text("←", 22, foreground());
        backBtn.setPadding(0, dp(4), dp(16), dp(4));
        backBtn.setOnClickListener(v -> closeScreen());
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

        LinearLayout actionsRow = new LinearLayout(getActivity());
        actionsRow.setGravity(Gravity.CENTER_VERTICAL);
        actionsRow.setPadding(0, dp(12), 0, 0);

        TextView playAllBtn = actionButton("Play all", true);
        playAllBtn.setOnClickListener(v -> playFromIndex(0));
        actionsRow.addView(playAllBtn, new LinearLayout.LayoutParams(-2, -2));

        View spacer = new View(getActivity());
        actionsRow.addView(spacer, new LinearLayout.LayoutParams(dp(12), dp(1)));

        TextView deleteBtn = actionButton("Delete playlist", false);
        deleteBtn.setOnClickListener(v -> confirmDeletePlaylist());
        actionsRow.addView(deleteBtn, new LinearLayout.LayoutParams(-2, -2));

        header.addView(actionsRow, new LinearLayout.LayoutParams(-1, -2));
        return header;
    }

    private TextView actionButton(String label, boolean primary) {
        TextView btn = text(label, 14, primary ? background() : foreground());
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        btn.setPadding(dp(16), dp(8), dp(16), dp(8));

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(18));
        if (primary) {
            bg.setColor(foreground());
        } else {
            bg.setColor(Utils.adjustColorBrightness(background(), 0.08f));
            bg.setStroke(dp(1), rippleColor());
        }
        btn.setBackground(bg);
        return btn;
    }

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
        countView.setText(playlistTracks.size() + (playlistTracks.size() == 1 ? " track" : " tracks"));

        if (playlistTracks.isEmpty()) {
            TextView empty = text("No tracks in this playlist", 15, secondary());
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(60), 0, 0);
            tracksContainer.addView(empty);
            return;
        }

        for (int i = 0; i < playlistTracks.size(); i++) {
            final int index = i;
            OfflineTrack track = playlistTracks.get(i);
            tracksContainer.addView(createTrackRow(track, index));
        }
    }

    private View createTrackRow(OfflineTrack track, int index) {
        LinearLayout row = new LinearLayout(getActivity());
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(7), dp(8), dp(7));
        row.setBackground(new RippleDrawable(ColorStateList.valueOf(rippleColor()), null,
                CustomDialog.createRoundedBackground(ROW_CORNER_DP, Color.WHITE)));

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

        // Remove from playlist only (does NOT delete the audio file)
        ImageButton removeBtn = icon("yt_outline_experimental_trash_can_vd_theme_24", "Remove from playlist");
        removeBtn.setOnClickListener(v -> removeTrackFromPlaylist(track));
        row.addView(removeBtn, new LinearLayout.LayoutParams(dp(ICON_BUTTON_DP), dp(ICON_BUTTON_DP)));

        row.setOnClickListener(v -> playFromIndex(index));
        return row;
    }

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

    private void confirmDeletePlaylist() {
        AlertDialog dialog = new AlertDialog.Builder(getActivity())
                .setTitle("Delete playlist")
                .setMessage("Delete \"" + (playlist != null ? playlist.name() : "") + "\"? Downloaded songs will not be deleted.")
                .setPositiveButton("Delete", (d, w) -> {
                    playlistManager.deletePlaylist(playlistId);
                    if (onPlaylistChanged != null) onPlaylistChanged.run();
                    Utils.showToastShort("Playlist deleted");
                    closeScreen();
                })
                .setNegativeButton("Cancel", null)
                .create();
        dialog.show();
    }

    private void playFromIndex(int startIndex) {
        if (playlistTracks.isEmpty() || startIndex < 0 || startIndex >= playlistTracks.size()) {
            Utils.showToastShort("No local tracks available in this playlist");
            return;
        }

        ArrayList<String> queue = new ArrayList<>();
        for (OfflineTrack t : playlistTracks) {
            queue.add(t.audioFile().getAbsolutePath());
        }

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

    private void closeScreen() {
        if (getFragmentManager() != null) {
            getFragmentManager().popBackStack();
        }
    }

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
