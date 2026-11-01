/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2528
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.downloads;

import static app.morphe.extension.music.patches.downloads.OfflineUi.ICON_BUTTON_DP;
import static app.morphe.extension.music.patches.downloads.OfflineUi.ROW_ARTWORK_PIXELS;
import static app.morphe.extension.music.patches.downloads.OfflineUi.background;
import static app.morphe.extension.music.patches.downloads.OfflineUi.foreground;
import static app.morphe.extension.music.patches.downloads.OfflineUi.formatSize;
import static app.morphe.extension.music.patches.downloads.OfflineUi.rippleColor;
import static app.morphe.extension.music.patches.downloads.OfflineUi.secondary;
import static app.morphe.extension.shared.StringRef.str;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.AnimatedVectorDrawable;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.OvalShape;
import android.os.Bundle;
import android.os.Environment;
import android.preference.PreferenceFragment;
import android.util.Pair;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListPopupWindow;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.IntConsumer;

import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.search.BaseSearchViewController;
import app.morphe.extension.shared.theme.ThemeUtils;
import app.morphe.extension.shared.ui.CustomDialog;

/** Local catalogue with a compact player matching the stock bottom player of YouTube Music. */
@SuppressWarnings("deprecation")
public final class LocalDownloadsFragment extends PreferenceFragment
        implements OfflinePlaybackService.PlaybackListener {

    private static final int SEEK_BAR_HEIGHT_DP = 3;

    /** The bar is drawn thin but stays easy to hit. */
    private static final int SEEK_BAR_TOUCH_DP = 18;
    private static final int SEEK_THUMB_DP = 12;

    private static final int SEARCH_BAR_HEIGHT_DP = 46;
    private static final long SEARCH_ANIMATION_MILLISECONDS = 180;

    /** The list of playlists scrolls in its own fixed height once it has more rows than this. */
    private static final int MAX_UNSCROLLED_PLAYLISTS = 4;
    private static final int PLAYLISTS_LIST_HEIGHT_DP = 210;
    private static final int MAX_UNSCROLLED_DIALOG_PLAYLISTS = 5;
    private static final int DIALOG_LIST_HEIGHT_DP = 260;

    private static final int MENU_WIDTH_DP = 220;
    private static final int MENU_CORNER_DP = 12;

    private File musicRoot;
    private OfflineUi ui;
    private OfflinePlaylistManager playlistManager;
    private List<OfflinePlaylist> playlists = new ArrayList<>();

    /** Keeps the order the tracks were picked in, for the playlist made from them. */
    private final Set<String> selectedTrackPaths = new LinkedHashSet<>();
    private boolean selectionMode;
    private boolean playlistsExpanded;

    private LinearLayout root;
    private LinearLayout catalogue;
    @Nullable
    private LocalPlaylistPage playlistPage;

    private ImageView miniArtwork;
    private TextView miniTitle;
    private TextView miniArtist;
    private ImageButton miniPlay;
    private ImageButton miniPrevious;
    private ImageButton miniNext;
    private SeekBar miniSeek;
    private LinearLayout miniPlayer;
    private LinearLayout songsList;
    private LinearLayout playlistsSection;
    private View selectionBar;
    /** Part of the playlists section, so it only exists while that has been expanded. */
    @Nullable
    private TextView selectionButton;

    private boolean showingPause;

    /**
     * Every downloaded track, read once per refresh instead of on every list or playback update.
     */
    private List<OfflineTrack> tracks = new ArrayList<>();

    /** Each row, kept so the playing one can be marked without redrawing the list. */
    private final List<View> rowViews = new ArrayList<>();

    /** In the same order as {@link #displayedTrackPaths}. */
    private final List<CheckBox> rowCheckBoxes = new ArrayList<>();

    private List<String> displayedTrackPaths = new ArrayList<>();
    private String playingPath = "";
    private String query = "";
    private TextView summary;
    private EditText searchBar;
    private boolean userSeeking;

    /** How the catalogue is ordered, remembered between visits. */
    private enum SortOrder {
        ARTIST, TITLE, SIZE;

        static SortOrder saved() {
            try {
                return valueOf(Settings.DOWNLOADS_SORT.get());
            } catch (Exception ignored) {
                return ARTIST;
            }
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle state) {
        musicRoot = new File(getActivity().getExternalFilesDir(Environment.DIRECTORY_MUSIC), "Morphe");
        ui = new OfflineUi(getActivity());
        playlistManager = new OfflinePlaylistManager(musicRoot);
        playlists = playlistManager.loadPlaylists();

        root = new LinearLayout(getActivity());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(background());

        catalogue = new LinearLayout(getActivity());
        catalogue.setOrientation(LinearLayout.VERTICAL);

        // The header stays out of the list, so typing a query does not rebuild the search bar.
        catalogue.addView(createHeader(), new LinearLayout.LayoutParams(-1, -2));

        ScrollView scroll = new ScrollView(getActivity());
        songsList = new LinearLayout(getActivity());
        songsList.setOrientation(LinearLayout.VERTICAL);
        songsList.setPadding(ui.dp(8), 0, ui.dp(8), ui.dp(10));
        scroll.addView(songsList);
        catalogue.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(catalogue, new LinearLayout.LayoutParams(-1, 0, 1));

        showTracks();

        miniPlayer = createStockMiniPlayer();
        miniPlayer.setVisibility(View.GONE);
        root.addView(miniPlayer, new LinearLayout.LayoutParams(-1, -2));
        return root;
    }

    @Override
    public void onStart() {
        super.onStart();
        OfflinePlaybackService.addListener(this);
    }

    @Override
    public void onStop() {
        OfflinePlaybackService.removeListener(this);
        super.onStop();
    }

    /**
     * Back navigation of every kind ends up here through the finish hook of the activity.
     *
     * @return If back navigation was used up by the playlist page.
     */
    public boolean handleBack() {
        if (playlistPage == null) return false;

        if (!playlistPage.handleBack()) closePlaylistPage();
        return true;
    }

    private void openPlaylistPage(OfflinePlaylist playlist) {
        playlistPage = new LocalPlaylistPage(this, getActivity(), ui, playlistManager,
                playlist.id(), playingPath);
        catalogue.setVisibility(View.GONE);
        root.addView(playlistPage.view(), 0, new LinearLayout.LayoutParams(-1, 0, 1));
        playlistPage.refresh();
    }

    void closePlaylistPage() {
        root.removeView(playlistPage.view());
        playlistPage = null;
        catalogue.setVisibility(View.VISIBLE);
        reloadPlaylists();
    }

    private void reloadPlaylists() {
        playlists = playlistManager.loadPlaylists();
        renderPlaylistsSection();
    }

    private void togglePlaylistsSection() {
        playlistsExpanded = !playlistsExpanded;
        renderPlaylistsSection();
    }

    private void renderPlaylistsSection() {
        playlistsSection.removeAllViews();
        playlistsSection.setVisibility(playlistsExpanded ? View.VISIBLE : View.GONE);
        selectionButton = null;
        if (!playlistsExpanded) return;

        LinearLayout topBar = new LinearLayout(getActivity());
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(ui.dp(4), ui.dp(6), ui.dp(4), ui.dp(6));

        TextView sectionHeader = ui.text(str("morphe_music_downloads_playlists_section",
                playlists.size()), 15, foreground());
        sectionHeader.setTypeface(Typeface.DEFAULT_BOLD);
        topBar.addView(sectionHeader, new LinearLayout.LayoutParams(0, -2, 1));

        selectionButton = ui.pillButton(selectionButtonLabel(), false);
        selectionButton.setOnClickListener(v -> setSelectionMode(!selectionMode));
        topBar.addView(selectionButton, new LinearLayout.LayoutParams(-2, -2));
        playlistsSection.addView(topBar, new LinearLayout.LayoutParams(-1, -2));

        if (playlists.isEmpty()) {
            TextView empty = ui.text(str("morphe_music_downloads_playlists_empty"), 13, secondary());
            empty.setPadding(ui.dp(6), ui.dp(8), ui.dp(6), ui.dp(8));
            playlistsSection.addView(empty);
        } else {
            LinearLayout rows = new LinearLayout(getActivity());
            rows.setOrientation(LinearLayout.VERTICAL);
            for (OfflinePlaylist playlist : playlists) {
                rows.addView(playlistRow(playlist));
            }

            ScrollView scroll = new ScrollView(getActivity());
            scroll.addView(rows);
            int height = playlists.size() > MAX_UNSCROLLED_PLAYLISTS
                    ? ui.dp(PLAYLISTS_LIST_HEIGHT_DP)
                    : ViewGroup.LayoutParams.WRAP_CONTENT;
            playlistsSection.addView(scroll, new LinearLayout.LayoutParams(-1, height));
        }

        LinearLayout hideBar = new LinearLayout(getActivity());
        hideBar.setGravity(Gravity.CENTER);
        hideBar.setPadding(0, ui.dp(6), 0, ui.dp(4));

        TextView hideButton = ui.pillButton(str("morphe_music_downloads_playlists_hide"), false);
        hideButton.setOnClickListener(v -> togglePlaylistsSection());
        hideBar.addView(hideButton, new LinearLayout.LayoutParams(-2, -2));
        playlistsSection.addView(hideBar, new LinearLayout.LayoutParams(-1, -2));

        View divider = new View(getActivity());
        divider.setBackgroundColor(rippleColor());
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(-1, ui.dp(1));
        dividerParams.topMargin = ui.dp(6);
        playlistsSection.addView(divider, dividerParams);
    }

    private View playlistRow(OfflinePlaylist playlist) {
        LinearLayout row = new LinearLayout(getActivity());
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(ui.dp(10), ui.dp(8), ui.dp(10), ui.dp(8));
        row.setBackground(ui.rowBackground(false));

        LinearLayout labels = new LinearLayout(getActivity());
        labels.setOrientation(LinearLayout.VERTICAL);

        TextView title = ui.text(playlist.name(), 15, foreground());
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setSingleLine(true);

        TextView subtitle = ui.text(OfflineUi.trackCount(playlist.trackPaths().size()), 13, secondary());
        subtitle.setSingleLine(true);

        labels.addView(title);
        labels.addView(subtitle);
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));

        ImageButton playButton = ui.icon("yt_fill_experimental_play_vd_theme_24",
                str("morphe_music_downloads_playlist_play"));
        playButton.setOnClickListener(v -> playPlaylist(playlist));
        row.addView(playButton, new LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)));

        row.setOnClickListener(v -> openPlaylistPage(playlist));
        return row;
    }

    /**
     * Reads the catalogue from disk. Every other method works off this snapshot.
     */
    private void loadTracks() {
        tracks = new ArrayList<>();
        File[] files = musicRoot.listFiles(file -> file.isFile()
                && (file.getName().endsWith(".webm") || file.getName().endsWith(".m4a")));
        if (files == null) return;

        for (File file : files) {
            tracks.add(OfflineTrack.load(file));
        }
        tracks.sort(comparator());
    }

    private Comparator<OfflineTrack> comparator() {
        Comparator<OfflineTrack> byTitle =
                Comparator.comparing(track -> track.displayTitle().toLowerCase(Locale.ROOT));

        return switch (SortOrder.saved()) {
            case TITLE -> byTitle;
            case SIZE -> Comparator.comparingLong(
                    (OfflineTrack track) -> track.audioFile().length()).reversed();
            default -> Comparator.comparing(
                    (OfflineTrack track) -> track.displayArtist().toLowerCase(Locale.ROOT))
                    .thenComparing(byTitle);
        };
    }

    private boolean matchesQuery(OfflineTrack track) {
        if (query.isEmpty()) return true;
        return track.displayTitle().toLowerCase(Locale.ROOT).contains(query)
                || track.displayArtist().toLowerCase(Locale.ROOT).contains(query);
    }

    /**
     * The search bar and the summary sit above the list, so they survive a redraw of the rows.
     */
    private LinearLayout createHeader() {
        LinearLayout header = new LinearLayout(getActivity());
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(6));

        // Hidden until asked for, the way the settings screen reveals its own search.
        searchBar = CustomDialog.createSearchBar(getActivity(),
                str("morphe_music_downloads_search_hint"), text -> {
                    query = text.trim().toLowerCase(Locale.ROOT);
                    showTracks();
                });
        searchBar.setVisibility(View.GONE);
        searchBar.setAlpha(0f);
        header.addView(searchBar, new LinearLayout.LayoutParams(-1, 0));

        LinearLayout line = new LinearLayout(getActivity());
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(0, ui.dp(8), 0, 0);

        summary = ui.text("", 13, secondary());
        line.addView(summary, new LinearLayout.LayoutParams(0, -2, 1));

        ImageButton search = ui.icon(BaseSearchViewController.getSearchIconDrawable(),
                str("morphe_music_downloads_search_hint"));
        search.setOnClickListener(v -> toggleSearch());
        line.addView(search, new LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)));

        ImageButton sort = ui.icon("yt_outline_experimental_sort_vd_theme_24",
                str("morphe_music_downloads_sort"));
        sort.setOnClickListener(v -> showSortMenu(sort));
        line.addView(sort, new LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)));

        ImageButton playlistsButton = ui.icon("yt_outline_experimental_playlist_vd_theme_24",
                str("morphe_music_downloads_playlists"));
        playlistsButton.setOnClickListener(v -> togglePlaylistsSection());
        line.addView(playlistsButton, new LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)));

        ImageButton deleteAll = ui.icon("yt_outline_experimental_trash_can_vd_theme_24",
                str("morphe_music_downloads_delete_all"));
        deleteAll.setOnClickListener(v -> confirmDeleteAll());
        line.addView(deleteAll, new LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)));

        header.addView(line, new LinearLayout.LayoutParams(-1, -2));

        TextView createButton = ui.pillButton(str("morphe_music_downloads_playlist_create"), false);
        createButton.setOnClickListener(v -> createPlaylistFromSelection());

        TextView addButton = ui.pillButton(str("morphe_music_downloads_playlist_add"), false);
        addButton.setOnClickListener(v -> addSelectionToPlaylist());

        TextView clearButton = ui.pillButton(str("morphe_music_downloads_playlist_clear_selection"), false);
        clearButton.setTextColor(secondary());
        clearButton.setOnClickListener(v -> setSelectionMode(false));

        selectionBar = ui.pillBar(createButton, addButton, clearButton);
        selectionBar.setPadding(0, ui.dp(8), 0, ui.dp(4));
        selectionBar.setVisibility(View.GONE);
        header.addView(selectionBar, new LinearLayout.LayoutParams(-1, -2));

        playlistsSection = new LinearLayout(getActivity());
        playlistsSection.setOrientation(LinearLayout.VERTICAL);
        playlistsSection.setPadding(0, ui.dp(6), 0, ui.dp(4));
        playlistsSection.setVisibility(View.GONE);
        header.addView(playlistsSection, new LinearLayout.LayoutParams(-1, -2));

        return header;
    }

    private String selectionButtonLabel() {
        return selectionMode
                ? str("morphe_music_downloads_playlist_selected", selectedTrackPaths.size())
                : str("morphe_music_downloads_playlist_new");
    }

    private void setSelectionMode(boolean enabled) {
        selectionMode = enabled;
        selectedTrackPaths.clear();
        updateSelectionUi();
    }

    private void toggleSelected(OfflineTrack track) {
        String path = track.audioFile().getAbsolutePath();
        if (!selectedTrackPaths.remove(path)) {
            selectedTrackPaths.add(path);
        }
        updateSelectionUi();
    }

    private void updateSelectionUi() {
        if (selectionButton != null) selectionButton.setText(selectionButtonLabel());
        selectionBar.setVisibility(selectionMode ? View.VISIBLE : View.GONE);

        for (int i = 0; i < rowCheckBoxes.size(); i++) {
            CheckBox checkBox = rowCheckBoxes.get(i);
            checkBox.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
            checkBox.setChecked(selectedTrackPaths.contains(displayedTrackPaths.get(i)));
        }
    }

    private void createPlaylistFromSelection() {
        if (selectedTrackPaths.isEmpty()) {
            Utils.showToastShort(str("morphe_music_downloads_playlist_select_first"));
            return;
        }
        showCreatePlaylistDialog(new ArrayList<>(selectedTrackPaths), true);
    }

    private void addSelectionToPlaylist() {
        if (selectedTrackPaths.isEmpty()) {
            Utils.showToastShort(str("morphe_music_downloads_playlist_select_first"));
            return;
        }
        showAddToPlaylistDialog(new ArrayList<>(selectedTrackPaths), true);
    }

    /**
     * @param fromSelection If the tracks are the current selection, which then ends once saved.
     */
    private void showAddToPlaylistDialog(List<String> trackPaths, boolean fromSelection) {
        if (playlists.isEmpty()) {
            showCreatePlaylistDialog(trackPaths, fromSelection);
            return;
        }

        Pair<Dialog, LinearLayout> dialogPair = CustomDialog.create(getActivity(),
                str("morphe_music_downloads_playlist_add"), null, null,
                str("morphe_music_downloads_playlist_new_button"),
                () -> showCreatePlaylistDialog(trackPaths, fromSelection),
                () -> {
                }, null, null, true);
        Dialog dialog = dialogPair.first;
        LinearLayout dialogRoot = dialogPair.second;

        LinearLayout rows = new LinearLayout(getActivity());
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(0, ui.dp(4), 0, ui.dp(8));

        for (OfflinePlaylist playlist : playlists) {
            LinearLayout row = new LinearLayout(getActivity());
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12));
            row.setBackground(ui.rowBackground(false));

            TextView name = ui.text(playlist.name(), 15, foreground());
            name.setTypeface(Typeface.DEFAULT_BOLD);
            name.setSingleLine(true);
            row.addView(name, new LinearLayout.LayoutParams(0, -2, 1));

            TextView count = ui.text(OfflineUi.trackCount(playlist.trackPaths().size()), 13, secondary());
            row.addView(count, new LinearLayout.LayoutParams(-2, -2));

            row.setOnClickListener(v -> {
                dialog.dismiss();
                addToPlaylist(playlist, trackPaths, fromSelection);
            });
            rows.addView(row);
        }

        ScrollView scroll = new ScrollView(getActivity());
        scroll.addView(rows);
        int height = playlists.size() > MAX_UNSCROLLED_DIALOG_PLAYLISTS
                ? ui.dp(DIALOG_LIST_HEIGHT_DP)
                : ViewGroup.LayoutParams.WRAP_CONTENT;
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(-1, height);
        scrollParams.bottomMargin = ui.dp(12);

        // Between the title and the buttons.
        dialogRoot.addView(scroll, dialogRoot.getChildCount() - 1, scrollParams);
        dialog.show();
    }

    private void addToPlaylist(OfflinePlaylist playlist, List<String> trackPaths, boolean fromSelection) {
        try {
            playlistManager.addTracks(playlist.id(), trackPaths);
            Utils.showToastShort(str("morphe_music_downloads_playlist_added", playlist.name()));
            onTracksSaved(fromSelection);
        } catch (IOException ex) {
            Logger.printException(() -> "Could not add tracks to playlist", ex);
            Utils.showToastShort(str("morphe_music_downloads_playlist_save_failed"));
        }
    }

    private void showCreatePlaylistDialog(List<String> trackPaths, boolean fromSelection) {
        ui.showPlaylistNameDialog(str("morphe_music_downloads_playlist_create"),
                str("morphe_music_downloads_playlist_create_button"), "", name -> {
                    try {
                        playlistManager.createPlaylist(name, trackPaths);
                        playlistsExpanded = true;
                        onTracksSaved(fromSelection);
                    } catch (IOException ex) {
                        Logger.printException(() -> "Could not create playlist", ex);
                        Utils.showToastShort(str("morphe_music_downloads_playlist_save_failed"));
                    }
                });
    }

    private void onTracksSaved(boolean fromSelection) {
        if (fromSelection) setSelectionMode(false);
        reloadPlaylists();
    }

    /** Opens the search field, or closes it and drops the query. */
    private void toggleSearch() {
        final boolean opening = searchBar.getVisibility() != View.VISIBLE;
        InputMethodManager keyboard = (InputMethodManager)
                getActivity().getSystemService(Context.INPUT_METHOD_SERVICE);

        if (opening) {
            searchBar.setVisibility(View.VISIBLE);
            slideSearchBar(ui.dp(SEARCH_BAR_HEIGHT_DP));
            searchBar.animate().alpha(1f).setDuration(SEARCH_ANIMATION_MILLISECONDS).start();

            searchBar.requestFocus();
            if (keyboard != null) keyboard.showSoftInput(searchBar, InputMethodManager.SHOW_IMPLICIT);
            return;
        }

        if (keyboard != null) keyboard.hideSoftInputFromWindow(searchBar.getWindowToken(), 0);
        searchBar.setText("");
        searchBar.clearFocus();

        slideSearchBar(0);
        searchBar.animate().alpha(0f).setDuration(SEARCH_ANIMATION_MILLISECONDS)
                .withEndAction(() -> searchBar.setVisibility(View.GONE)).start();
    }

    /** The height is animated by hand, since a collapsing child otherwise jumps the list. */
    private void slideSearchBar(int target) {
        ValueAnimator animator = ValueAnimator.ofInt(searchBar.getHeight(), target);
        animator.setDuration(SEARCH_ANIMATION_MILLISECONDS);
        animator.addUpdateListener(value -> {
            searchBar.getLayoutParams().height = (int) value.getAnimatedValue();
            searchBar.requestLayout();
        });
        animator.start();
    }

    private void showSortMenu(View anchor) {
        String[] items = {
                str("morphe_music_downloads_sort_artist"),
                str("morphe_music_downloads_sort_title"),
                str("morphe_music_downloads_sort_size"),
        };
        showMenu(anchor, items, SortOrder.saved().ordinal(), position -> {
            Settings.DOWNLOADS_SORT.save(SortOrder.values()[position].name());
            showTracks();
        });
    }

    @Nullable
    private OfflineTrack findByPath(String path) {
        if (path.isEmpty()) return null;
        for (OfflineTrack track : tracks) {
            if (track.audioFile().getAbsolutePath().equals(path)) return track;
        }
        return null;
    }

    private void showTracks() {
        loadTracks();
        songsList.removeAllViews();
        rowViews.clear();
        rowCheckBoxes.clear();
        displayedTrackPaths = new ArrayList<>();

        long totalBytes = 0;
        for (OfflineTrack track : tracks) {
            totalBytes += track.audioFile().length();
        }
        summary.setText(str("morphe_music_downloads_summary",
                tracks.size(), formatSize(totalBytes)));

        for (OfflineTrack track : tracks) {
            if (!matchesQuery(track)) continue;
            displayedTrackPaths.add(track.audioFile().getAbsolutePath());
            songsList.addView(songRow(track));
        }

        if (displayedTrackPaths.isEmpty()) {
            TextView empty = ui.text(str(tracks.isEmpty()
                    ? "morphe_music_downloads_empty"
                    : "morphe_music_downloads_nothing_found"), 16, secondary());
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, ui.dp(80), 0, 0);
            songsList.addView(empty);
            return;
        }
        markPlayingRow();
    }

    private View songRow(OfflineTrack track) {
        LinearLayout row = ui.trackRow();
        rowViews.add(row);

        CheckBox checkBox = new CheckBox(getActivity());
        checkBox.setButtonTintList(ColorStateList.valueOf(foreground()));
        checkBox.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
        checkBox.setChecked(selectedTrackPaths.contains(track.audioFile().getAbsolutePath()));
        checkBox.setOnClickListener(v -> toggleSelected(track));
        row.addView(checkBox, new LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)));
        rowCheckBoxes.add(checkBox);

        ui.addTrackInfo(row, track);

        ImageButton menu = ui.icon("yt_outline_experimental_overflow_vertical_vd_theme_24",
                str("morphe_music_downloads_actions_for", track.displayTitle()));
        menu.setOnClickListener(v -> showTrackMenu(menu, track));
        row.addView(menu, new LinearLayout.LayoutParams(ui.dp(ICON_BUTTON_DP), ui.dp(ICON_BUTTON_DP)));

        row.setOnClickListener(v -> {
            if (selectionMode) toggleSelected(track);
            else play(track, displayedTrackPaths);
        });
        return row;
    }

    /** Lifts the card of the track being played out of the background. */
    private void markPlayingRow() {
        int playingIndex = displayedTrackPaths.indexOf(playingPath);
        for (int i = 0; i < rowViews.size(); i++) {
            rowViews.get(i).setBackground(ui.rowBackground(i == playingIndex));
        }
    }

    private LinearLayout createStockMiniPlayer() {
        LinearLayout outer = new LinearLayout(getActivity());
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setBackgroundColor(ThemeUtils.getDialogBackgroundColor());

        // The surface color of a theme can sit very close to its background, so the bar is
        // separated by a rule rather than by the fill alone.
        View divider = new View(getActivity());
        divider.setBackgroundColor(rippleColor());
        outer.addView(divider, new LinearLayout.LayoutParams(-1, ui.dp(1)));
        // Lifts the bar off the gesture area, so the seek bar is not on the screen edge.
        outer.setPadding(0, 0, 0, ui.dp(12));

        LinearLayout line = new LinearLayout(getActivity());
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(ui.dp(16), 0, 0, 0);

        miniArtwork = ui.artworkView(null);
        line.addView(miniArtwork, new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));

        LinearLayout labels = new LinearLayout(getActivity());
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setGravity(Gravity.CENTER_VERTICAL);
        labels.setPadding(ui.dp(16), 0, ui.dp(4), 0);

        miniTitle = ui.text("", 14, foreground());
        miniTitle.setSingleLine(true);
        miniTitle.setTypeface(Typeface.DEFAULT_BOLD);

        miniArtist = ui.text("", 14, secondary());
        miniArtist.setSingleLine(true);

        labels.addView(miniTitle);
        labels.addView(miniArtist);
        line.addView(labels, new LinearLayout.LayoutParams(0, ui.dp(56), 1));

        miniPrevious = ui.icon("yt_fill_experimental_skip_previous_vd_theme_24",
                str("morphe_music_downloads_previous"));
        miniPrevious.setOnClickListener(v -> OfflinePlaybackService.skipPrevious(getActivity()));
        line.addView(miniPrevious, new LinearLayout.LayoutParams(ui.dp(ICON_BUTTON_DP), ui.dp(ICON_BUTTON_DP)));

        miniPlay = ui.icon("yt_fill_experimental_play_vd_theme_24",
                str("morphe_music_downloads_play_pause"));
        // The morph drawable of the app is 48dp, so whatever the button shows is scaled down to
        // the 24dp of the icons beside it.
        miniPlay.setScaleType(ImageView.ScaleType.FIT_CENTER);
        miniPlay.setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12));
        miniPlay.setOnClickListener(v -> OfflinePlaybackService.toggle(getActivity()));
        line.addView(miniPlay, new LinearLayout.LayoutParams(ui.dp(ICON_BUTTON_DP), ui.dp(ICON_BUTTON_DP)));

        miniNext = ui.icon("yt_fill_experimental_skip_next_vd_theme_24",
                str("morphe_music_downloads_next"));
        miniNext.setOnClickListener(v -> OfflinePlaybackService.skipNext(getActivity()));
        line.addView(miniNext, new LinearLayout.LayoutParams(ui.dp(ICON_BUTTON_DP), ui.dp(ICON_BUTTON_DP)));

        outer.addView(line, new LinearLayout.LayoutParams(-1, ui.dp(60)));
        outer.addView(createSeekBar(), new LinearLayout.LayoutParams(-1, ui.dp(SEEK_BAR_TOUCH_DP)));
        return outer;
    }

    private SeekBar createSeekBar() {
        miniSeek = new SeekBar(getActivity());
        // The bar keeps the gutters of the list, so it reads as a control rather than an edge.
        miniSeek.setPadding(ui.dp(16), 0, ui.dp(16), 0);
        miniSeek.setProgressDrawable(createSeekBarDrawable());
        // The stock thumb draws a ripple far wider than the bar, so it only appears while dragging.
        miniSeek.setThumb(null);
        miniSeek.setThumbOffset(0);
        miniSeek.setBackground(null);
        miniSeek.setSplitTrack(false);
        miniSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onStartTrackingTouch(SeekBar bar) {
                userSeeking = true;
                bar.setThumb(createThumb());
                bar.setThumbOffset(ui.dp(SEEK_THUMB_DP) / 2);
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                userSeeking = false;
                bar.setThumb(null);
                OfflinePlaybackService.seekTo(bar.getProgress());
            }

            @Override
            public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
            }
        });
        return miniSeek;
    }

    private Drawable createThumb() {
        ShapeDrawable thumb = new ShapeDrawable(new OvalShape());
        thumb.setIntrinsicWidth(ui.dp(SEEK_THUMB_DP));
        thumb.setIntrinsicHeight(ui.dp(SEEK_THUMB_DP));
        thumb.getPaint().setColor(ThemeUtils.getAppForegroundColor());
        return thumb;
    }

    /**
     * The thickness comes from the drawable because {@code setMinHeight} and
     * {@code setMaxHeight} are only available from API 29.
     */
    private Drawable createSeekBarDrawable() {
        final int foreground = ThemeUtils.getAppForegroundColor();

        // Dimming the foreground works on any background, unlike lightening a black one.
        Drawable track = roundedBar(Utils.adjustColorBrightness(foreground, 0.3f));
        Drawable played = roundedBar(foreground);

        LayerDrawable layers = new LayerDrawable(new Drawable[]{
                track, new ClipDrawable(played, Gravity.START, ClipDrawable.HORIZONTAL)});
        layers.setId(0, android.R.id.background);
        layers.setId(1, android.R.id.progress);

        // A progress drawable is stretched to the view, and the thickness can only be capped
        // with setMaxHeight from API 29, so the band is inset to the height it should have.
        final int inset = (ui.dp(SEEK_BAR_TOUCH_DP) - ui.dp(SEEK_BAR_HEIGHT_DP)) / 2;
        layers.setLayerInset(0, 0, inset, 0, inset);
        layers.setLayerInset(1, 0, inset, 0, inset);
        return layers;
    }

    private Drawable roundedBar(int color) {
        return CustomDialog.createRoundedBackground(SEEK_BAR_HEIGHT_DP, color);
    }

    private void showTrackMenu(View anchor, OfflineTrack track) {
        String[] items = {
                str("morphe_music_downloads_play"),
                str("morphe_music_downloads_playlist_add"),
                str("morphe_music_downloads_delete_track"),
        };
        showMenu(anchor, items, -1, position -> {
            switch (position) {
                case 1 -> showAddToPlaylistDialog(
                        Collections.singletonList(track.audioFile().getAbsolutePath()), false);
                case 2 -> confirmDelete(track);
                default -> play(track, displayedTrackPaths);
            }
        });
    }

    private void playPlaylist(OfflinePlaylist playlist) {
        List<String> queue = playlist.trackPaths();
        if (queue.isEmpty()) {
            Utils.showToastShort(str("morphe_music_downloads_playlist_no_tracks"));
            return;
        }
        play(OfflineTrack.load(new File(queue.get(0))), queue);
    }

    /**
     * @param selected Index drawn as the active choice, or -1 when the items are plain actions.
     */
    private void showMenu(View anchor, String[] items, int selected, IntConsumer onPick) {
        ListPopupWindow popup = new ListPopupWindow(getActivity());
        popup.setAnchorView(anchor);
        popup.setDropDownGravity(Gravity.END);
        popup.setWidth(ui.dp(MENU_WIDTH_DP));
        popup.setModal(true);
        popup.setBackgroundDrawable(menuBackground());
        // The list draws its own square selector, which would bleed past the rounded background.
        popup.setListSelector(new RippleDrawable(ColorStateList.valueOf(rippleColor()), null,
                CustomDialog.createRoundedBackground(MENU_CORNER_DP, Color.WHITE)));
        popup.setAdapter(new ArrayAdapter<>(getActivity(), 0, items) {
            @NonNull
            @Override
            public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                TextView view = ui.text(getItem(position), 15,
                        selected < 0 || position == selected ? foreground() : secondary());
                view.setPadding(ui.dp(20), ui.dp(14), ui.dp(20), ui.dp(14));
                return view;
            }
        });
        popup.setOnItemClickListener((parent, view, position, id) -> {
            popup.dismiss();
            onPick.accept(position);
        });
        popup.show();
    }

    /**
     * The surface color of a theme can sit very close to its background, so the menu is outlined
     * rather than left to rely on the two being far enough apart.
     */
    private Drawable menuBackground() {
        GradientDrawable background = new GradientDrawable();
        background.setColor(ThemeUtils.getDialogBackgroundColor());
        background.setCornerRadius(ui.dp(MENU_CORNER_DP));
        background.setStroke(ui.dp(1), rippleColor());
        return background;
    }

    private void confirmDelete(OfflineTrack track) {
        confirm(str("morphe_music_downloads_delete_track_title"),
                str("morphe_music_downloads_delete_track_message", track.displayTitle()),
                () -> deleteTrack(track));
    }

    private void confirmDeleteAll() {
        if (tracks.isEmpty()) return;
        confirm(str("morphe_music_downloads_delete_all_title"),
                str("morphe_music_downloads_delete_all_message"), this::deleteAll);
    }

    private void confirm(String title, String message, Runnable onConfirm) {
        CustomDialog.create(getActivity(), title, message, null,
                str("morphe_music_downloads_delete"), onConfirm, () -> {
                }, null, null, false).first.show();
    }

    private void deleteAll() {
        for (OfflineTrack track : new ArrayList<>(tracks)) {
            OfflineStorage.delete(track.audioFile());
            deleteSidecars(track.videoId());
        }
        setSelectionMode(false);
        showTracks();
        reloadPlaylists();
        Utils.showToastShort(str("morphe_music_downloads_all_deleted"));
    }

    private void deleteSidecars(String videoId) {
        OfflineStorage.delete(new File(musicRoot, videoId + ".json"));
        OfflineStorage.delete(new File(musicRoot, videoId + ".jpg"));
        OfflineStorage.delete(new File(musicRoot, videoId + ".webm.part"));
        OfflineStorage.delete(new File(musicRoot, videoId + ".m4a.part"));
    }

    private void deleteTrack(OfflineTrack track) {
        final boolean deleted = track.audioFile().delete();
        deleteSidecars(track.videoId());

        if (!deleted) {
            Utils.showToastShort(str("morphe_music_downloads_track_delete_failed"));
            return;
        }
        selectedTrackPaths.remove(track.audioFile().getAbsolutePath());
        showTracks();
        reloadPlaylists();
        Utils.showToastShort(str("morphe_music_downloads_track_deleted"));
    }

    /**
     * @param queuePaths The tracks that Previous and Next move through, including this track.
     */
    void play(OfflineTrack track, List<String> queuePaths) {
        try {
            final int queueIndex = Math.max(0, queuePaths.indexOf(track.audioFile().getAbsolutePath()));
            OfflinePlaybackService.play(getActivity(), track, new ArrayList<>(queuePaths), queueIndex);
            applyTrackVisuals(track);
        } catch (Exception ex) {
            Logger.printException(() -> "Offline playback failed: " + track.audioFile(), ex);
            Utils.showToastShort(str("morphe_music_downloads_play_failed"));
        }
    }

    @Override
    public void onPlaybackChanged(String path, String title, boolean playing,
                                  int position, int duration) {
        if (getActivity() == null) return;

        getActivity().runOnUiThread(() -> {
            if (miniPlayer == null) return;
            final boolean wasVisible = miniPlayer.getVisibility() == View.VISIBLE;
            miniPlayer.setVisibility(title.isEmpty() ? View.GONE : View.VISIBLE);

            // Decoding the cover on every tick would read the disk twice a second.
            if (!path.equals(playingPath)) {
                playingPath = path;
                OfflineTrack track = findByPath(path);
                if (track != null) applyTrackVisuals(track);
                else miniTitle.setText(title);

                markPlayingRow();
                if (playlistPage != null) playlistPage.markPlayingRow(path);
            }

            // Not only on a new track, since the same track can be played from another queue.
            setButtonEnabled(miniPrevious, OfflinePlaybackService.hasPrevious());
            setButtonEnabled(miniNext, OfflinePlaybackService.hasNext());

            // This runs on every playback tick, so the icon only morphs when the state
            // actually flips while the bar is on screen to see it happen.
            updatePlayIcon(playing, wasVisible && playing != showingPause);
            miniSeek.setMax(Math.max(1, duration));
            if (!userSeeking) miniSeek.setProgress(position, true);
        });
    }

    private void updatePlayIcon(boolean playing, boolean morph) {
        showingPause = playing;
        if (morph && startIconMorph(playing)) return;

        miniPlay.setImageDrawable(ResourceUtils.getDrawable(playing
                ? "yt_fill_experimental_pause_vd_theme_24"
                : "yt_fill_experimental_play_vd_theme_24"));
    }

    /**
     * The app ships the drawable its own player uses to morph between the two icons.
     */
    private boolean startIconMorph(boolean playing) {
        String name = playing
                ? "player_play_pause_vector_transition"
                : "player_pause_play_vector_transition";

        int identifier = ResourceUtils.getDrawableIdentifier(name + "_delhi");
        if (identifier == 0) identifier = ResourceUtils.getDrawableIdentifier(name);

        Activity activity = getActivity();
        if (identifier == 0 || activity == null) return false;

        // It fills itself with a theme attribute of the player, so it has to be read with the
        // theme of this screen, which defines that attribute as white for the icon tint to work.
        Drawable drawable = activity.getDrawable(identifier);
        if (!(drawable instanceof AnimatedVectorDrawable morph)) return false;

        miniPlay.setImageDrawable(morph);
        morph.start();
        return true;
    }

    private void applyTrackVisuals(OfflineTrack track) {
        miniTitle.setText(track.displayTitle());
        miniArtist.setText(track.displayArtist());

        Bitmap bitmap = track.artwork(ROW_ARTWORK_PIXELS);
        if (bitmap == null) return;

        miniArtwork.clearColorFilter();
        miniArtwork.setPadding(0, 0, 0, 0);
        miniArtwork.setImageBitmap(bitmap);
        miniArtwork.setScaleType(ImageView.ScaleType.CENTER_CROP);
    }

    private void setButtonEnabled(ImageButton button, boolean enabled) {
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1f : .35f);
    }
}
