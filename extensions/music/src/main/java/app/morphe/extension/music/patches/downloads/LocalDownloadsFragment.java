/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2528
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.downloads;

import static app.morphe.extension.music.patches.downloads.OfflineUi.ICON_BUTTON_DP;
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
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.AnimatedVectorDrawable;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.ColorDrawable;
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
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListPopupWindow;
import android.widget.ListView;
import android.widget.ProgressBar;
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
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.IntConsumer;

import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.search.BaseSearchViewController;
import app.morphe.extension.shared.theme.ThemeUtils;
import app.morphe.extension.shared.ui.CustomDialog;

/**
 * Local catalogue with a compact player matching the stock bottom player of YouTube Music.
 * <p>
 * A whole liked music library can be downloaded, so the catalogue may hold thousands of tracks.
 * It is read off the main thread, only the rows on screen are built, and their covers are decoded
 * in the background. Doing any of this for every track while the screen opens blocks the app long
 * enough for the system to close it.
 */
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

    private static final int EMPTY_TOP_PADDING_DP = 80;
    private static final int LOADING_INDICATOR_DP = 40;

    /** Downloads can finish every second, so the list is refreshed a moment after the last. */
    private static final long REFRESH_DELAY_MILLISECONDS = 2000;

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
    private ListView songsList;
    private TrackAdapter adapter;
    private TextView emptyView;
    private ProgressBar loadingIndicator;
    private LinearLayout playlistsSection;
    private View selectionBar;
    /** Part of the playlists section, so it only exists while that has been expanded. */
    @Nullable
    private TextView selectionButton;

    private boolean showingPause;

    /** Every downloaded track, sorted, as read by the last load. */
    private List<OfflineTrack> tracks = new ArrayList<>();
    private final Map<String, OfflineTrack> tracksByPath = new HashMap<>();
    private long totalBytes;

    /** The tracks that match the search. */
    private final List<OfflineTrack> displayedTracks = new ArrayList<>();

    /** Paths of {@link #displayedTracks}, the queue that playing from the list moves through. */
    private List<String> displayedTrackPaths = new ArrayList<>();

    /** Lets a load that finishes after the screen was closed, or reloaded, be ignored. */
    private int loadGeneration;
    /** If a read of the folder is under way, so another is not started on top of it. */
    private boolean reading;
    /** The snapshot the list shows, so the same one is not sorted and drawn again. */
    @Nullable
    private OfflineCatalogue.Snapshot appliedSnapshot;

    /** Lists the downloads that finished while the screen is open. */
    private final Runnable refresh = () -> {
        if (getActivity() == null || root == null) return;
        if (reading) scheduleRefresh();
        else loadCatalogue();
    };
    private final Runnable onTrackSaved = this::scheduleRefresh;

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
        Activity activity = getActivity();
        musicRoot = new File(activity.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "Morphe");
        ui = new OfflineUi(activity);
        playlistManager = new OfflinePlaylistManager(musicRoot);

        root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(background());

        catalogue = new LinearLayout(activity);
        catalogue.setOrientation(LinearLayout.VERTICAL);

        // The header stays out of the list, so typing a query does not rebuild the search bar.
        catalogue.addView(createHeader(), new LinearLayout.LayoutParams(-1, -2));
        catalogue.addView(createSongsList(), new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(catalogue, new LinearLayout.LayoutParams(-1, 0, 1));

        miniPlayer = createStockMiniPlayer();
        miniPlayer.setVisibility(View.GONE);
        root.addView(miniPlayer, new LinearLayout.LayoutParams(-1, -2));
        return root;
    }

    @Override
    public void onStart() {
        super.onStart();
        OfflinePlaybackService.addListener(this);
        LocalDownloadManager.addSavedListener(onTrackSaved);
    }

    private void scheduleRefresh() {
        root.removeCallbacks(refresh);
        root.postDelayed(refresh, REFRESH_DELAY_MILLISECONDS);
    }

    @Override
    public void onResume() {
        super.onResume();
        // Also lists the downloads that finished while the screen was away. Only the files the
        // catalogue did not have are read, so this is cheap when nothing changed.
        if (!reading) loadCatalogue();
    }

    @Override
    public void onStop() {
        LocalDownloadManager.removeSavedListener(onTrackSaved);
        root.removeCallbacks(refresh);
        OfflinePlaybackService.removeListener(this);
        OfflineArtworkLoader.evictAll();
        super.onStop();
    }

    @Override
    public void onDestroyView() {
        // A load still running reports to views that are gone.
        loadGeneration++;
        OfflineArtworkLoader.cancelPending(ui);
        super.onDestroyView();
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
     * Reads the catalogue off the main thread. The catalogue read on the last visit is shown
     * meanwhile, if there is one.
     */
    private void loadCatalogue() {
        final int generation = ++loadGeneration;
        File directory = musicRoot;
        OfflinePlaylistManager manager = playlistManager;
        reading = true;

        OfflineCatalogue.Snapshot shown = OfflineCatalogue.cached();
        final boolean showingCache = shown != null
                && shown.directory().equals(directory.getAbsolutePath());
        if (showingCache) {
            if (shown != appliedSnapshot) applyCatalogue(shown);
        } else if (appliedSnapshot == null) {
            setLoading(true);
        }

        Utils.runOnBackgroundThread(() -> {
            // The playlists are a few small files, so they are shown before the tracks are read.
            List<OfflinePlaylist> playlistsRead = null;
            try {
                playlistsRead = manager.loadPlaylists();
            } catch (Exception ex) {
                Logger.printException(() -> "Could not read the local playlists", ex);
            }
            List<OfflinePlaylist> loadedPlaylists = playlistsRead;
            if (loadedPlaylists != null) {
                Utils.runOnMainThread(() -> {
                    if (generation != loadGeneration || getActivity() == null) return;
                    playlists = loadedPlaylists;
                    renderPlaylistsSection();
                });
            }

            OfflineCatalogue.Snapshot snapshot = null;
            try {
                snapshot = OfflineCatalogue.read(directory);
            } catch (Exception ex) {
                Logger.printException(() -> "Could not read the downloads", ex);
            }

            OfflineCatalogue.Snapshot read = snapshot;
            Utils.runOnMainThread(() -> {
                if (generation != loadGeneration || getActivity() == null) return;
                reading = false;

                if (read == null) {
                    if (appliedSnapshot == null) applyCatalogue(null);
                } else if (read != appliedSnapshot && read == OfflineCatalogue.cached()) {
                    // Still what the folder holds: nothing was deleted from the screen meanwhile.
                    applyCatalogue(read);
                }
                setLoading(false);
            });
        });
    }

    /**
     * @param snapshot The catalogue to show, or null for an empty one.
     */
    private void applyCatalogue(@Nullable OfflineCatalogue.Snapshot snapshot) {
        appliedSnapshot = snapshot;
        List<OfflineTrack> sorted = snapshot == null
                ? new ArrayList<>()
                : new ArrayList<>(snapshot.tracks());
        sorted.sort(comparator());
        tracks = sorted;
        indexTracks();
        showTracks();

        // The track playing may have been reported before the catalogue was read.
        OfflineTrack playing = findByPath(playingPath);
        if (playing != null) applyTrackVisuals(playing);
    }

    private void indexTracks() {
        tracksByPath.clear();
        long total = 0;
        for (OfflineTrack track : tracks) {
            tracksByPath.put(track.audioFile().getAbsolutePath(), track);
            total += track.sizeBytes();
        }
        totalBytes = total;
    }

    private void setLoading(boolean value) {
        loadingIndicator.setVisibility(value ? View.VISIBLE : View.GONE);
        updateEmptyView();
    }

    private void updateEmptyView() {
        final boolean empty = loadingIndicator.getVisibility() != View.VISIBLE
                && displayedTracks.isEmpty();
        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (empty) {
            emptyView.setText(str(tracks.isEmpty()
                    ? "morphe_music_downloads_empty"
                    : "morphe_music_downloads_nothing_found"));
        }
    }

    /**
     * Compares without allocating, since sorting thousands of tracks compares tens of thousands
     * of times, on the main thread when the sort order changes.
     */
    private Comparator<OfflineTrack> comparator() {
        Comparator<OfflineTrack> byTitle = Comparator.comparing(
                OfflineTrack::displayTitle, String.CASE_INSENSITIVE_ORDER);

        return switch (SortOrder.saved()) {
            case TITLE -> byTitle;
            case SIZE -> Comparator.<OfflineTrack>comparingLong(OfflineTrack::sizeBytes).reversed();
            default -> Comparator.<OfflineTrack, String>comparing(
                    OfflineTrack::displayArtist, String.CASE_INSENSITIVE_ORDER)
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
                    songsList.setSelection(0);
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

    /**
     * Only the rows on screen exist, and they are reused while scrolling, since the catalogue
     * can hold thousands of tracks.
     */
    private View createSongsList() {
        Activity activity = getActivity();
        FrameLayout frame = new FrameLayout(activity);

        songsList = new ListView(activity);
        songsList.setDivider(null);
        // Each row draws its own rounded ripple.
        songsList.setSelector(new ColorDrawable(Color.TRANSPARENT));
        songsList.setItemsCanFocus(true);
        songsList.setPadding(ui.dp(8), 0, ui.dp(8), ui.dp(10));
        songsList.setClipToPadding(false);
        adapter = new TrackAdapter();
        songsList.setAdapter(adapter);
        frame.addView(songsList, new FrameLayout.LayoutParams(-1, -1));

        emptyView = ui.text("", 16, secondary());
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setPadding(0, ui.dp(EMPTY_TOP_PADDING_DP), 0, 0);
        emptyView.setVisibility(View.GONE);
        frame.addView(emptyView, new FrameLayout.LayoutParams(-1, -2));

        loadingIndicator = new ProgressBar(activity);
        loadingIndicator.setIndeterminateTintList(ColorStateList.valueOf(foreground()));
        loadingIndicator.setVisibility(View.GONE);
        FrameLayout.LayoutParams indicatorParams = new FrameLayout.LayoutParams(
                ui.dp(LOADING_INDICATOR_DP), ui.dp(LOADING_INDICATOR_DP), Gravity.CENTER_HORIZONTAL);
        indicatorParams.topMargin = ui.dp(EMPTY_TOP_PADDING_DP);
        frame.addView(loadingIndicator, indicatorParams);
        return frame;
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
        // The rows on screen show their check boxes again, the others when they are reused.
        adapter.notifyDataSetChanged();
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
            tracks.sort(comparator());
            showTracks();
            songsList.setSelection(0);
        });
    }

    @Nullable
    private OfflineTrack findByPath(String path) {
        if (path.isEmpty()) return null;
        return tracksByPath.get(path);
    }

    /** The track of an audio file, from the catalogue if it has been read, or else from disk. */
    OfflineTrack trackFor(String path) {
        OfflineTrack track = findByPath(path);
        return track != null ? track : OfflineTrack.load(new File(path));
    }

    /** Filters the catalogue in memory, so typing a query does not read the disk again. */
    private void showTracks() {
        displayedTracks.clear();
        List<String> paths = new ArrayList<>();
        for (OfflineTrack track : tracks) {
            if (!matchesQuery(track)) continue;
            displayedTracks.add(track);
            paths.add(track.audioFile().getAbsolutePath());
        }
        displayedTrackPaths = paths;

        summary.setText(str("morphe_music_downloads_summary",
                tracks.size(), formatSize(totalBytes)));
        adapter.notifyDataSetChanged();
        updateEmptyView();
    }

    /** The views of one row, kept with it so the row can be reused for another track. */
    private static final class RowViews {
        final LinearLayout row;
        final CheckBox checkBox;
        final OfflineUi.TrackInfoViews info;
        final ImageButton menu;

        /** Which card the row shows, so a redraw does not cut the ripple of a tap short. */
        boolean highlighted;

        RowViews(LinearLayout row, CheckBox checkBox, OfflineUi.TrackInfoViews info,
                 ImageButton menu) {
            this.row = row;
            this.checkBox = checkBox;
            this.info = info;
            this.menu = menu;
        }
    }

    private final class TrackAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return displayedTracks.size();
        }

        @Override
        public OfflineTrack getItem(int position) {
            return displayedTracks.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, @Nullable View convertView, ViewGroup parent) {
            RowViews views = convertView != null && convertView.getTag() instanceof RowViews reused
                    ? reused
                    : createRow();
            bindRow(views, displayedTracks.get(position));
            return views.row;
        }
    }

    private RowViews createRow() {
        LinearLayout row = ui.trackRow();
        row.setBackground(ui.rowBackground(false));

        CheckBox checkBox = new CheckBox(getActivity());
        checkBox.setButtonTintList(ColorStateList.valueOf(foreground()));
        row.addView(checkBox, new LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)));

        OfflineUi.TrackInfoViews info = ui.createTrackInfo(row);

        ImageButton menu = ui.icon("yt_outline_experimental_overflow_vertical_vd_theme_24", "");
        row.addView(menu, new LinearLayout.LayoutParams(ui.dp(ICON_BUTTON_DP), ui.dp(ICON_BUTTON_DP)));

        RowViews views = new RowViews(row, checkBox, info, menu);
        row.setTag(views);
        return views;
    }

    private void bindRow(RowViews views, OfflineTrack track) {
        String path = track.audioFile().getAbsolutePath();

        views.checkBox.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
        views.checkBox.setChecked(selectedTrackPaths.contains(path));
        views.checkBox.setOnClickListener(v -> toggleSelected(track));

        ui.bindTrackInfo(views.info, track);

        views.menu.setContentDescription(str("morphe_music_downloads_actions_for", track.displayTitle()));
        views.menu.setOnClickListener(v -> showTrackMenu(views.menu, track));

        views.row.setOnClickListener(v -> {
            if (selectionMode) toggleSelected(track);
            else play(track, displayedTrackPaths);
        });

        final boolean highlighted = path.equals(playingPath);
        if (views.highlighted != highlighted) {
            views.highlighted = highlighted;
            views.row.setBackground(ui.rowBackground(highlighted));
        }
    }

    /** Lifts the card of the track being played out of the background. */
    private void markPlayingRow() {
        adapter.notifyDataSetChanged();
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

        miniArtwork = ui.artworkView();
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
        play(trackFor(queue.get(0)), queue);
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

    /** Thousands of files take a while to remove, so the disk is left to a background thread. */
    private void deleteAll() {
        List<OfflineTrack> deleted = new ArrayList<>(tracks);
        File directory = musicRoot;
        final int generation = ++loadGeneration;

        setSelectionMode(false);
        // A read under way must not bring the tracks back once they are gone, and no other read
        // starts while they go.
        OfflineCatalogue.clear();
        applyCatalogue(null);
        reading = true;
        setLoading(true);

        Utils.runOnBackgroundThread(() -> {
            try {
                for (OfflineTrack track : deleted) {
                    OfflineStorage.delete(track.audioFile());
                    deleteSidecars(directory, track.videoId());
                }
            } catch (Exception ex) {
                Logger.printException(() -> "Could not delete every download", ex);
            }
            OfflineCatalogue.clear();

            Utils.runOnMainThread(() -> {
                Utils.showToastShort(str("morphe_music_downloads_all_deleted"));
                if (generation != loadGeneration || getActivity() == null) return;
                // Whatever could not be deleted is listed again, with the playlists left.
                loadCatalogue();
            });
        });
    }

    private static void deleteSidecars(File directory, String videoId) {
        OfflineStorage.delete(new File(directory, videoId + ".json"));
        OfflineStorage.delete(new File(directory, videoId + ".jpg"));
        OfflineStorage.delete(new File(directory, videoId + ".webm.part"));
        OfflineStorage.delete(new File(directory, videoId + ".m4a.part"));
    }

    private void deleteTrack(OfflineTrack track) {
        final boolean deleted = track.audioFile().delete();
        deleteSidecars(musicRoot, track.videoId());

        if (!deleted) {
            Utils.showToastShort(str("morphe_music_downloads_track_delete_failed"));
            return;
        }
        selectedTrackPaths.remove(track.audioFile().getAbsolutePath());
        tracks.remove(track);
        indexTracks();
        appliedSnapshot = OfflineCatalogue.edited(musicRoot, tracks);
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
        // Shares the cover decoded for the row of the track, which is usually on screen.
        OfflineArtworkLoader.load(miniArtwork, track.artworkFile(), ui.rowArtworkPixels(), ui);
    }

    private void setButtonEnabled(ImageButton button, boolean enabled) {
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1f : .35f);
    }
}
