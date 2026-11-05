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
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
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
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
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

/** Local catalogue with a compact player and an expandable full player + queue page. */
@SuppressWarnings("deprecation")
public final class LocalDownloadsFragment extends PreferenceFragment
        implements OfflinePlaybackService.PlaybackListener {

    private static final int SEEK_BAR_HEIGHT_DP = 3;

    /** The bar is drawn thin but stays easy to hit. */
    private static final int SEEK_BAR_TOUCH_DP = 18;
    private static final int SEEK_THUMB_DP = 12;

    private static final int SEARCH_BAR_HEIGHT_DP = 46;
    private static final long SEARCH_ANIMATION_MILLISECONDS = 180;
    private static final long PLAYER_TRANSITION_MS = 260;
    private static final long REORDER_SHIFT_MS = 120;

    /** The list of playlists scrolls in its own fixed height once it has more rows than this. */
    private static final int MAX_UNSCROLLED_PLAYLISTS = 4;
    private static final int PLAYLISTS_LIST_HEIGHT_DP = 210;
    private static final int MAX_UNSCROLLED_DIALOG_PLAYLISTS = 5;
    private static final int DIALOG_LIST_HEIGHT_DP = 260;

    private static final int MENU_WIDTH_DP = 220;
    private static final int MENU_CORNER_DP = 12;
    private static final int FULL_ARTWORK_PIXELS = 1024;
    private static final int HERO_HEIGHT_DP = 340;
    private static final int DRAG_SCROLL_ZONE_DP = 48;
    private static final int DRAG_SCROLL_STEP_DP = 8;

    private static final int ICON_CHEVRON_DOWN = 1;
    private static final int ICON_SHUFFLE = 2;
    private static final int ICON_REPEAT = 3;
    private static final int ICON_REPEAT_ONE = 4;

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

    private FrameLayout root;
    private LinearLayout mainContainer;
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

    private LinearLayout fullPlayerPage;
    private ImageView fullArtwork;
    private TextView fullTitle;
    private TextView fullArtist;
    private SeekBar fullSeek;
    private TextView fullCurrentTime;
    private TextView fullTotalTime;
    private ImageButton fullPlay;
    private ImageButton fullPrevious;
    private ImageButton fullNext;
    private ImageButton fullShuffle;
    private ImageButton fullRepeat;
    private ScrollView fullQueueScroll;
    private LinearLayout fullQueueList;
    private boolean fullPlayerOpen;
    private boolean isInteractingWithQueue;
    private String lastQueueSignature = "";

    private LinearLayout songsList;
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

    /** Lets a load that finishes after the view was destroyed, or reloaded, be ignored. */
    private int loadGeneration;
    /** If a read of the folder is under way, so another is not started on top of it. */
    private boolean reading;
    /** The snapshot the list shows, so the same one is not sorted and drawn again. */
    @Nullable
    private OfflineCatalogue.Snapshot appliedSnapshot;

    /** Lists the downloads that finished while the screen is open. */
    private final Runnable refresh = () -> {
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

        root = new FrameLayout(getActivity());
        root.setBackgroundColor(background());

        mainContainer = new LinearLayout(getActivity());
        mainContainer.setOrientation(LinearLayout.VERTICAL);

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
        mainContainer.addView(catalogue, new LinearLayout.LayoutParams(-1, 0, 1));

        miniPlayer = createStockMiniPlayer();
        miniPlayer.setVisibility(View.GONE);
        mainContainer.addView(miniPlayer, new LinearLayout.LayoutParams(-1, -2));

        root.addView(mainContainer, new FrameLayout.LayoutParams(-1, -1));

        fullPlayerPage = createFullPlayerPage();
        fullPlayerPage.setVisibility(View.GONE);
        root.addView(fullPlayerPage, new FrameLayout.LayoutParams(-1, -1));

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
        setHostToolbarVisible(true);
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
     * @return If back navigation was used up by the full player or playlist page.
     */
    public boolean handleBack() {
        if (fullPlayerOpen) {
            closeFullPlayer();
            return true;
        }
        if (playlistPage == null) return false;

        if (!playlistPage.handleBack()) closePlaylistPage();
        return true;
    }

    private void openFullPlayer() {
        if (playingPath.isEmpty() || fullPlayerOpen) return;
        fullPlayerOpen = true;
        setHostToolbarVisible(false);
        updateShuffleRepeatUi();
        refreshFullQueue(true);

        fullPlayerPage.animate().cancel();
        fullPlayerPage.setVisibility(View.VISIBLE);

        int height = root.getHeight() > 0
                ? root.getHeight()
                : getResources().getDisplayMetrics().heightPixels;
        fullPlayerPage.setTranslationY(height * 0.85f);
        fullPlayerPage.setAlpha(0.3f);
        fullPlayerPage.animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(PLAYER_TRANSITION_MS)
                .setInterpolator(new DecelerateInterpolator(1.6f))
                .withEndAction(() -> miniPlayer.setVisibility(View.GONE))
                .start();
    }

    private void closeFullPlayer() {
        if (!fullPlayerOpen) return;
        fullPlayerOpen = false;
        setHostToolbarVisible(true);
        miniPlayer.setVisibility(playingPath.isEmpty() ? View.GONE : View.VISIBLE);

        int height = root.getHeight() > 0
                ? root.getHeight()
                : getResources().getDisplayMetrics().heightPixels;
        fullPlayerPage.animate().cancel();
        fullPlayerPage.animate()
                .translationY(height * 0.85f)
                .alpha(0f)
                .setDuration(PLAYER_TRANSITION_MS - 30)
                .setInterpolator(new AccelerateInterpolator(1.5f))
                .withEndAction(() -> {
                    if (!fullPlayerOpen) {
                        fullPlayerPage.setVisibility(View.GONE);
                        fullPlayerPage.setTranslationY(0f);
                        fullPlayerPage.setAlpha(1f);
                    }
                })
                .start();
    }

    /**
     * Hides the parent Activity's toolbar while the full-screen player is open so the cover
     * sits flush against the top edge.
     */
    private void setHostToolbarVisible(boolean visible) {
        Activity activity = getActivity();
        if (activity == null) return;
        if (activity.getActionBar() != null) {
            if (visible) activity.getActionBar().show();
            else activity.getActionBar().hide();
        }
        if (root == null) return;
        View content = activity.findViewById(android.R.id.content);
        ViewParent parent = root.getParent();
        while (parent instanceof ViewGroup group && parent != content) {
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (!isDescendantOf(root, child)) {
                    child.setVisibility(visible ? View.VISIBLE : View.GONE);
                }
            }
            parent = group.getParent();
        }
    }

    private boolean isDescendantOf(View target, View potentialAncestor) {
        if (target == potentialAncestor) return true;
        ViewParent parent = target.getParent();
        while (parent != null) {
            if (parent == potentialAncestor) return true;
            parent = parent.getParent();
        }
        return false;
    }

    private void openPlaylistPage(OfflinePlaylist playlist) {
        playlistPage = new LocalPlaylistPage(this, getActivity(), ui, playlistManager,
                playlist.id(), playingPath);
        catalogue.setVisibility(View.GONE);
        mainContainer.addView(playlistPage.view(), 0, new LinearLayout.LayoutParams(-1, 0, 1));
        playlistPage.refresh();
    }

    void closePlaylistPage() {
        mainContainer.removeView(playlistPage.view());
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

        OfflineCatalogue.Snapshot cached = OfflineCatalogue.cached();
        if (cached == null) setLoading(true);
        else if (cached != appliedSnapshot) applyCatalogue(cached);

        Utils.runOnBackgroundThread(() -> {
            try {
                // The playlists are a few small files, so they are shown before the tracks are
                // read.
                List<OfflinePlaylist> loadedPlaylists = manager.loadPlaylists();
                Utils.runOnMainThread(() -> {
                    if (generation != loadGeneration) return;
                    playlists = loadedPlaylists;
                    renderPlaylistsSection();
                });

                OfflineCatalogue.update(directory);
            } catch (Exception ex) {
                Logger.printException(() -> "Could not read the downloads", ex);
            }

            Utils.runOnMainThread(() -> {
                if (generation != loadGeneration) return;
                reading = false;
                // What the catalogue now holds, which may come from another read, such as the
                // one of the screen before a rotation, or from a track deleted meanwhile.
                OfflineCatalogue.Snapshot latest = OfflineCatalogue.cached();
                if (latest != appliedSnapshot) applyCatalogue(latest);
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
        for (OfflineTrack track : tracks) {
            if (track.audioFile().getAbsolutePath().equals(path)) return track;
        }
        File file = new File(path);
        return file.isFile() ? OfflineTrack.load(file) : null;
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
        line.setOnClickListener(v -> openFullPlayer());

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

    private LinearLayout createFullPlayerPage() {
        LinearLayout page = new LinearLayout(getActivity());
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(background());
        page.setClickable(true);

        // 1. Hero Artwork + Gradient Scrim + Top Bar + Overlaid Track Title/Artist
        FrameLayout hero = new FrameLayout(getActivity());

        // Top-aligned crop preserves the upper portion of square album covers.
        fullArtwork = new ImageView(getActivity()) {
            @Override
            protected boolean setFrame(int l, int t, int r, int b) {
                boolean changed = super.setFrame(l, t, r, b);
                applyTopCropMatrix(this, r - l, b - t);
                return changed;
            }
        };
        fullArtwork.setBackgroundColor(Utils.adjustColorBrightness(foreground(), 0.15f));
        fullArtwork.setScaleType(ImageView.ScaleType.MATRIX);
        hero.addView(fullArtwork, new FrameLayout.LayoutParams(-1, -1));

        GradientDrawable scrim = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x66000000, 0x00000000, background()});
        View scrimView = new View(getActivity());
        scrimView.setBackground(scrim);
        hero.addView(scrimView, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout topBar = new LinearLayout(getActivity());
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(ui.dp(8), ui.dp(10), ui.dp(8), 0);

        ImageButton collapseBtn = ui.icon(resolveDrawable(ICON_CHEVRON_DOWN,
                "yt_outline_chevron_down_vd_theme_24",
                "yt_outline_experimental_chevron_down_vd_theme_24"), "Minimize");
        collapseBtn.setColorFilter(foreground());
        collapseBtn.setOnClickListener(v -> closeFullPlayer());
        topBar.addView(collapseBtn, new LinearLayout.LayoutParams(ui.dp(ICON_BUTTON_DP), ui.dp(ICON_BUTTON_DP)));

        topBar.addView(new View(getActivity()), new LinearLayout.LayoutParams(0, 0, 1));

        ImageButton menuBtn = ui.icon("yt_outline_experimental_overflow_vertical_vd_theme_24", "More");
        menuBtn.setOnClickListener(v -> {
            OfflineTrack current = findByPath(playingPath);
            if (current != null) showTrackMenu(menuBtn, current);
        });
        topBar.addView(menuBtn, new LinearLayout.LayoutParams(ui.dp(ICON_BUTTON_DP), ui.dp(ICON_BUTTON_DP)));
        hero.addView(topBar, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        LinearLayout titleBlock = new LinearLayout(getActivity());
        titleBlock.setOrientation(LinearLayout.VERTICAL);
        titleBlock.setPadding(ui.dp(20), 0, ui.dp(20), ui.dp(10));

        fullTitle = ui.text("", 22, foreground());
        fullTitle.setTypeface(Typeface.DEFAULT_BOLD);
        fullTitle.setSingleLine(true);

        fullArtist = ui.text("", 14, secondary());
        fullArtist.setSingleLine(true);

        titleBlock.addView(fullTitle);
        titleBlock.addView(fullArtist);
        hero.addView(titleBlock, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        page.addView(hero, new LinearLayout.LayoutParams(-1, ui.dp(HERO_HEIGHT_DP)));

        // 2. Full Player SeekBar + Time Labels
        fullSeek = createFullSeekBar();
        page.addView(fullSeek, new LinearLayout.LayoutParams(-1, ui.dp(SEEK_BAR_TOUCH_DP)));

        LinearLayout timeRow = new LinearLayout(getActivity());
        timeRow.setPadding(ui.dp(20), 0, ui.dp(20), ui.dp(4));
        fullCurrentTime = ui.text("0:00", 12, secondary());
        fullTotalTime = ui.text("0:00", 12, secondary());
        timeRow.addView(fullCurrentTime, new LinearLayout.LayoutParams(0, -2, 1));
        timeRow.addView(fullTotalTime, new LinearLayout.LayoutParams(-2, -2));
        page.addView(timeRow, new LinearLayout.LayoutParams(-1, -2));

        // 3. Transport Controls Row (Shuffle | Prev | Large Play/Pause | Next | Repeat)
        LinearLayout controls = new LinearLayout(getActivity());
        controls.setGravity(Gravity.CENTER_VERTICAL);
        controls.setPadding(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(10));

        fullShuffle = ui.icon(resolveDrawable(ICON_SHUFFLE,
                "yt_outline_arrow_shuffle_vd_theme_24",
                "yt_outline_experimental_shuffle_vd_theme_24"), "Shuffle");
        fullShuffle.setColorFilter(foreground());
        fullShuffle.setOnClickListener(v -> {
            OfflinePlaybackService.toggleShuffle();
            updateShuffleRepeatUi();
            refreshFullQueue(true);
        });
        controls.addView(fullShuffle, new LinearLayout.LayoutParams(ui.dp(ICON_BUTTON_DP), ui.dp(ICON_BUTTON_DP)));
        controls.addView(new View(getActivity()), new LinearLayout.LayoutParams(0, 0, 1));

        fullPrevious = ui.icon("yt_fill_experimental_skip_previous_vd_theme_24",
                str("morphe_music_downloads_previous"));
        fullPrevious.setOnClickListener(v -> OfflinePlaybackService.skipPrevious(getActivity()));
        controls.addView(fullPrevious, new LinearLayout.LayoutParams(ui.dp(ICON_BUTTON_DP), ui.dp(ICON_BUTTON_DP)));
        controls.addView(new View(getActivity()), new LinearLayout.LayoutParams(0, 0, 1));

        fullPlay = new ImageButton(getActivity());
        fullPlay.setImageDrawable(ResourceUtils.getDrawable("yt_fill_experimental_play_vd_theme_24"));
        fullPlay.setColorFilter(background());
        fullPlay.setScaleType(ImageView.ScaleType.FIT_CENTER);
        fullPlay.setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(16));
        ShapeDrawable circle = new ShapeDrawable(new OvalShape());
        circle.getPaint().setColor(foreground());
        fullPlay.setBackground(new RippleDrawable(ColorStateList.valueOf(rippleColor()), circle, circle));
        fullPlay.setOnClickListener(v -> OfflinePlaybackService.toggle(getActivity()));
        controls.addView(fullPlay, new LinearLayout.LayoutParams(ui.dp(64), ui.dp(64)));
        controls.addView(new View(getActivity()), new LinearLayout.LayoutParams(0, 0, 1));

        fullNext = ui.icon("yt_fill_experimental_skip_next_vd_theme_24",
                str("morphe_music_downloads_next"));
        fullNext.setOnClickListener(v -> OfflinePlaybackService.skipNext(getActivity()));
        controls.addView(fullNext, new LinearLayout.LayoutParams(ui.dp(ICON_BUTTON_DP), ui.dp(ICON_BUTTON_DP)));
        controls.addView(new View(getActivity()), new LinearLayout.LayoutParams(0, 0, 1));

        fullRepeat = ui.icon(resolveDrawable(ICON_REPEAT,
                "yt_outline_arrow_repeat_vd_theme_24",
                "yt_outline_experimental_repeat_vd_theme_24"), "Repeat");
        fullRepeat.setColorFilter(foreground());
        fullRepeat.setOnClickListener(v -> {
            OfflinePlaybackService.cycleRepeatMode();
            updateShuffleRepeatUi();
        });
        controls.addView(fullRepeat, new LinearLayout.LayoutParams(ui.dp(ICON_BUTTON_DP), ui.dp(ICON_BUTTON_DP)));

        page.addView(controls, new LinearLayout.LayoutParams(-1, -2));

        // 4. Scrollable Full Queue List (Previous + Current + Upcoming)
        fullQueueScroll = new ScrollView(getActivity());
        fullQueueList = new LinearLayout(getActivity());
        fullQueueList.setOrientation(LinearLayout.VERTICAL);
        fullQueueList.setClipChildren(false);
        fullQueueList.setClipToPadding(false);
        fullQueueList.setPadding(ui.dp(8), ui.dp(4), ui.dp(8), ui.dp(16));
        fullQueueScroll.addView(fullQueueList);
        page.addView(fullQueueScroll, new LinearLayout.LayoutParams(-1, 0, 1));

        updateShuffleRepeatUi();
        return page;
    }

    private static void applyTopCropMatrix(ImageView view, int viewWidth, int viewHeight) {
        Drawable drawable = view.getDrawable();
        if (drawable == null || viewWidth <= 0 || viewHeight <= 0) return;
        int dWidth = drawable.getIntrinsicWidth();
        int dHeight = drawable.getIntrinsicHeight();
        if (dWidth <= 0 || dHeight <= 0) return;

        Matrix matrix = new Matrix();
        float scale = Math.max((float) viewWidth / dWidth, (float) viewHeight / dHeight);
        float dx = (viewWidth - dWidth * scale) * 0.5f;
        // Anchor near the top (10% vertical offset) so top text/heads on square covers are never clipped.
        float dy = (viewHeight - dHeight * scale) * 0.10f;
        matrix.setScale(scale, scale);
        matrix.postTranslate(Math.round(dx), Math.round(dy));
        view.setImageMatrix(matrix);
    }

    private SeekBar createFullSeekBar() {
        SeekBar bar = new SeekBar(getActivity());
        bar.setPadding(ui.dp(20), 0, ui.dp(20), 0);
        bar.setProgressDrawable(createSeekBarDrawable());
        bar.setThumb(createThumb());
        bar.setThumbOffset(ui.dp(SEEK_THUMB_DP) / 2);
        bar.setBackground(null);
        bar.setSplitTrack(false);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                userSeeking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                userSeeking = false;
                OfflinePlaybackService.seekTo(seekBar.getProgress());
            }

            @Override
            public void onProgressChanged(SeekBar seekBar, int value, boolean fromUser) {
                if (fromUser && fullCurrentTime != null) {
                    fullCurrentTime.setText(formatMillis(value));
                }
            }
        });
        return bar;
    }

    private void updateShuffleRepeatUi() {
        if (fullShuffle != null) {
            fullShuffle.setAlpha(OfflinePlaybackService.isShuffleEnabled() ? 1.0f : 0.4f);
        }
        if (fullRepeat != null) {
            int mode = OfflinePlaybackService.getRepeatMode();
            if (mode == OfflinePlaybackService.REPEAT_ONE) {
                fullRepeat.setImageDrawable(resolveDrawable(ICON_REPEAT_ONE,
                        "yt_outline_arrow_repeat_one_vd_theme_24",
                        "yt_outline_experimental_repeat_one_vd_theme_24"));
            } else {
                fullRepeat.setImageDrawable(resolveDrawable(ICON_REPEAT,
                        "yt_outline_arrow_repeat_vd_theme_24",
                        "yt_outline_experimental_repeat_vd_theme_24"));
            }
            fullRepeat.setColorFilter(foreground());
            fullRepeat.setAlpha(mode == OfflinePlaybackService.REPEAT_OFF ? 0.4f : 1.0f);
        }
    }

    private void refreshFullQueue(boolean scrollToCurrent) {
        if (fullQueueList == null) return;
        final int savedScrollY = fullQueueScroll != null ? fullQueueScroll.getScrollY() : 0;
        fullQueueList.removeAllViews();

        List<String> queue = OfflinePlaybackService.getQueue();
        int currentIndex = OfflinePlaybackService.getQueueIndex();

        for (int i = 0; i < queue.size(); i++) {
            OfflineTrack track = findByPath(queue.get(i));
            if (track == null) continue;

            LinearLayout row = ui.trackRow();
            boolean isPlaying = (i == currentIndex);
            row.setBackground(ui.rowBackground(isPlaying));
            row.setAlpha(i < currentIndex ? 0.72f : 1.0f);
            ui.addTrackInfo(row, track);

            TextView handle = ui.text("=", 20, secondary());
            handle.setGravity(Gravity.CENTER);
            row.addView(handle, new LinearLayout.LayoutParams(ui.dp(40), ui.dp(48)));

            attachQueueRowInteractions(row, handle);
            fullQueueList.addView(row);
        }

        if (fullQueueScroll != null) {
            fullQueueScroll.post(() -> {
                if (scrollToCurrent && currentIndex >= 0 && currentIndex < fullQueueList.getChildCount()) {
                    View targetRow = fullQueueList.getChildAt(currentIndex);
                    if (targetRow != null) {
                        fullQueueScroll.smoothScrollTo(0, targetRow.getTop());
                    }
                } else {
                    fullQueueScroll.scrollTo(0, savedScrollY);
                }
            });
        }
    }

    /**
     * Attaches horizontal swipe-to-remove on the row and vertical drag-to-reorder on its handle.
     * The dragged row stays attached throughout the gesture and shifts its neighbors via
     * translation, since detaching a touched view in Android cancels its active touch stream.
     */
    @SuppressLint("ClickableViewAccessibility")
    private void attachQueueRowInteractions(LinearLayout row, View dragHandle) {
        final int touchSlop = ViewConfiguration.get(getActivity()).getScaledTouchSlop();

        // 1. Horizontal Swipe-to-Remove + Tap-to-Play on the Row
        row.setOnTouchListener(new View.OnTouchListener() {
            private float downRawX;
            private float downRawY;
            private boolean swiping;
            private float baseAlpha = 1f;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN -> {
                        downRawX = event.getRawX();
                        downRawY = event.getRawY();
                        swiping = false;
                        baseAlpha = row.getAlpha();
                        row.setPressed(true);
                        return true;
                    }
                    case MotionEvent.ACTION_MOVE -> {
                        float dx = event.getRawX() - downRawX;
                        float dy = event.getRawY() - downRawY;
                        if (!swiping && Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy) * 1.25f) {
                            swiping = true;
                            isInteractingWithQueue = true;
                            row.setPressed(false);
                            if (fullQueueScroll != null) {
                                fullQueueScroll.requestDisallowInterceptTouchEvent(true);
                            }
                        }
                        if (swiping) {
                            row.setTranslationX(dx);
                            float progress = Math.min(1f, Math.abs(dx) / Math.max(1f, row.getWidth() * 0.75f));
                            row.setAlpha(baseAlpha * (1f - progress * 0.85f));
                            return true;
                        }
                        if (Math.abs(dy) > touchSlop) {
                            row.setPressed(false);
                        }
                        return false;
                    }
                    case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        row.setPressed(false);
                        if (swiping) {
                            float dx = event.getRawX() - downRawX;
                            if (fullQueueScroll != null) {
                                fullQueueScroll.requestDisallowInterceptTouchEvent(false);
                            }
                            if (event.getActionMasked() == MotionEvent.ACTION_UP
                                    && Math.abs(dx) > row.getWidth() * 0.32f) {
                                int removeIndex = fullQueueList.indexOfChild(row);
                                row.animate()
                                        .translationX(dx > 0 ? row.getWidth() : -row.getWidth())
                                        .alpha(0f)
                                        .setDuration(180)
                                        .withEndAction(() -> {
                                            isInteractingWithQueue = false;
                                            if (removeIndex >= 0) {
                                                OfflinePlaybackService.removeQueueItem(getActivity(), removeIndex);
                                                refreshFullQueue(false);
                                            }
                                        })
                                        .start();
                            } else {
                                row.animate()
                                        .translationX(0f)
                                        .alpha(baseAlpha)
                                        .setDuration(180)
                                        .withEndAction(() -> isInteractingWithQueue = false)
                                        .start();
                            }
                            return true;
                        } else if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                            float dx = Math.abs(event.getRawX() - downRawX);
                            float dy = Math.abs(event.getRawY() - downRawY);
                            if (dx < touchSlop && dy < touchSlop) {
                                int clickedIndex = fullQueueList.indexOfChild(row);
                                if (clickedIndex >= 0) {
                                    OfflinePlaybackService.playQueueIndex(getActivity(), clickedIndex);
                                }
                            }
                        }
                        return true;
                    }
                }
                return false;
            }
        });

        // 2. Non-detaching Vertical Drag-to-Reorder on the '=' Handle
        dragHandle.setOnTouchListener(new View.OnTouchListener() {
            private float downRawY;
            private int startScrollY;
            private int startIndex = -1;
            private int targetIndex = -1;
            private int rowHeight = 1;
            private float baseAlpha = 1f;
            private final int[] scrollLoc = new int[2];

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN -> {
                        startIndex = fullQueueList.indexOfChild(row);
                        if (startIndex < 0) return false;
                        targetIndex = startIndex;
                        downRawY = event.getRawY();
                        startScrollY = fullQueueScroll != null ? fullQueueScroll.getScrollY() : 0;
                        rowHeight = Math.max(1, row.getHeight());
                        baseAlpha = row.getAlpha();
                        isInteractingWithQueue = true;

                        if (fullQueueScroll != null) {
                            fullQueueScroll.requestDisallowInterceptTouchEvent(true);
                        }
                        row.animate().cancel();
                        row.setTranslationZ(ui.dp(8));
                        row.setScaleX(1.02f);
                        row.setScaleY(1.02f);
                        row.setAlpha(0.95f);
                        return true;
                    }
                    case MotionEvent.ACTION_MOVE -> {
                        if (startIndex < 0) return false;

                        // Auto-scroll when the dragged row nears the top or bottom of the ScrollView.
                        if (fullQueueScroll != null) {
                            fullQueueScroll.getLocationOnScreen(scrollLoc);
                            float yInScroll = event.getRawY() - scrollLoc[1];
                            int zone = ui.dp(DRAG_SCROLL_ZONE_DP);
                            int step = ui.dp(DRAG_SCROLL_STEP_DP);
                            if (yInScroll < zone && fullQueueScroll.canScrollVertically(-1)) {
                                fullQueueScroll.scrollBy(0, -step);
                            } else if (yInScroll > fullQueueScroll.getHeight() - zone
                                    && fullQueueScroll.canScrollVertically(1)) {
                                fullQueueScroll.scrollBy(0, step);
                            }
                        }

                        int currentScrollY = fullQueueScroll != null ? fullQueueScroll.getScrollY() : 0;
                        float totalDy = (event.getRawY() - downRawY) + (currentScrollY - startScrollY);

                        int count = fullQueueList.getChildCount();
                        float minDy = -startIndex * rowHeight;
                        float maxDy = (count - 1 - startIndex) * rowHeight;
                        float clampedDy = Math.max(minDy, Math.min(maxDy, totalDy));
                        row.setTranslationY(clampedDy);

                        int newTarget = Math.max(0, Math.min(count - 1,
                                startIndex + Math.round(clampedDy / rowHeight)));
                        if (newTarget != targetIndex) {
                            targetIndex = newTarget;
                            for (int i = 0; i < count; i++) {
                                if (i == startIndex) continue;
                                View sibling = fullQueueList.getChildAt(i);
                                float shift = 0f;
                                if (startIndex < targetIndex && i > startIndex && i <= targetIndex) {
                                    shift = -rowHeight;
                                } else if (startIndex > targetIndex && i < startIndex && i >= targetIndex) {
                                    shift = rowHeight;
                                }
                                sibling.animate()
                                        .translationY(shift)
                                        .setDuration(REORDER_SHIFT_MS)
                                        .start();
                            }
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (startIndex < 0) return false;
                        if (fullQueueScroll != null) {
                            fullQueueScroll.requestDisallowInterceptTouchEvent(false);
                        }
                        final int from = startIndex;
                        final int to = targetIndex;
                        final boolean commit = event.getActionMasked() == MotionEvent.ACTION_UP
                                && from >= 0 && to >= 0 && from != to;
                        startIndex = -1;
                        targetIndex = -1;

                        float finalY = commit ? (to - from) * rowHeight : 0f;
                        row.animate()
                                .translationY(finalY)
                                .scaleX(1f)
                                .scaleY(1f)
                                .alpha(baseAlpha)
                                .setDuration(REORDER_SHIFT_MS)
                                .withEndAction(() -> {
                                    row.setTranslationZ(0f);
                                    for (int i = 0; i < fullQueueList.getChildCount(); i++) {
                                        View child = fullQueueList.getChildAt(i);
                                        child.animate().cancel();
                                        child.setTranslationY(0f);
                                    }
                                    isInteractingWithQueue = false;
                                    if (commit) {
                                        OfflinePlaybackService.moveQueueItem(from, to);
                                    }
                                    refreshFullQueue(false);
                                })
                                .start();
                        return true;
                    }
                }
                return false;
            }
        });
    }

    private static String formatMillis(int millis) {
        int totalSeconds = Math.max(0, millis / 1000);
        String formatted = OfflineUi.formatDuration(totalSeconds);
        return formatted.isEmpty() ? "0:00" : formatted;
    }

    /**
     * Resolves a stock YouTube Music drawable by name, or falls back to a crisp vector path
     * if the APK version uses a different resource name.
     */
    private Drawable resolveDrawable(int fallbackType, String... candidateNames) {
        for (String name : candidateNames) {
            int id = ResourceUtils.getDrawableIdentifier(name);
            if (id != 0) {
                Drawable d = ResourceUtils.getDrawable(name);
                if (d != null) return d;
            }
        }
        return createVectorFallback(fallbackType);
    }

    private Drawable createVectorFallback(int type) {
        final int sizePx = ui.dp(24);
        return new Drawable() {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

            {
                paint.setColor(foreground());
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(ui.dp(2));
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setStrokeJoin(Paint.Join.ROUND);
            }

            @Override
            public void draw(@NonNull Canvas canvas) {
                float w = getBounds().width();
                float h = getBounds().height();
                float s = Math.min(w, h) / 24f;
                canvas.save();
                canvas.translate(getBounds().left + (w - 24f * s) / 2f,
                        getBounds().top + (h - 24f * s) / 2f);
                canvas.scale(s, s);
                paint.setStrokeWidth(2f);

                Path path = new Path();
                if (type == ICON_CHEVRON_DOWN) {
                    path.moveTo(6f, 9f);
                    path.lineTo(12f, 15f);
                    path.lineTo(18f, 9f);
                    canvas.drawPath(path, paint);
                } else if (type == ICON_SHUFFLE) {
                    path.moveTo(16f, 3f);
                    path.lineTo(21f, 3f);
                    path.lineTo(21f, 8f);
                    path.moveTo(4f, 20f);
                    path.lineTo(21f, 3f);
                    path.moveTo(21f, 16f);
                    path.lineTo(21f, 21f);
                    path.lineTo(16f, 21f);
                    path.moveTo(15f, 15f);
                    path.lineTo(21f, 21f);
                    path.moveTo(4f, 4f);
                    path.lineTo(9f, 9f);
                    canvas.drawPath(path, paint);
                } else if (type == ICON_REPEAT || type == ICON_REPEAT_ONE) {
                    path.moveTo(17f, 1f);
                    path.lineTo(21f, 5f);
                    path.lineTo(17f, 9f);
                    path.moveTo(3f, 11f);
                    path.lineTo(3f, 9f);
                    path.quadTo(3f, 5f, 7f, 5f);
                    path.lineTo(21f, 5f);
                    path.moveTo(7f, 23f);
                    path.lineTo(3f, 19f);
                    path.lineTo(7f, 15f);
                    path.moveTo(21f, 13f);
                    path.lineTo(21f, 15f);
                    path.quadTo(21f, 19f, 17f, 19f);
                    path.lineTo(3f, 19f);
                    canvas.drawPath(path, paint);
                    if (type == ICON_REPEAT_ONE) {
                        Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                        textPaint.setColor(paint.getColor());
                        textPaint.setTextSize(8.5f);
                        textPaint.setTypeface(Typeface.DEFAULT_BOLD);
                        textPaint.setTextAlign(Paint.Align.CENTER);
                        canvas.drawText("1", 12f, 14.8f, textPaint);
                    }
                }
                canvas.restore();
            }

            @Override
            public int getIntrinsicWidth() {
                return sizePx;
            }

            @Override
            public int getIntrinsicHeight() {
                return sizePx;
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
                if (generation != loadGeneration) return;
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
        appliedSnapshot = OfflineCatalogue.edited(tracks);
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
            if (title.isEmpty() && fullPlayerOpen) {
                closeFullPlayer();
            }
            final boolean wasVisible = miniPlayer.getVisibility() == View.VISIBLE;
            miniPlayer.setVisibility((title.isEmpty() || fullPlayerOpen) ? View.GONE : View.VISIBLE);

            final boolean trackChanged = !path.equals(playingPath);
            // Decoding the cover on every tick would read the disk twice a second.
            if (trackChanged) {
                playingPath = path;
                OfflineTrack track = findByPath(path);
                if (track != null) {
                    applyTrackVisuals(track);
                } else {
                    miniTitle.setText(title);
                    if (fullTitle != null) fullTitle.setText(title);
                }

                markPlayingRow();
                if (playlistPage != null) playlistPage.markPlayingRow(path);
            }

            // Not only on a new track, since the same track can be played from another queue.
            setButtonEnabled(miniPrevious, OfflinePlaybackService.hasPrevious());
            setButtonEnabled(miniNext, OfflinePlaybackService.hasNext());
            setButtonEnabled(fullPrevious, OfflinePlaybackService.hasPrevious());
            setButtonEnabled(fullNext, OfflinePlaybackService.hasNext());

            // This runs on every playback tick, so the icon only morphs when the state
            // actually flips while the bar is on screen to see it happen.
            updatePlayIcon(playing, wasVisible && playing != showingPause);
            fullPlay.setImageDrawable(ResourceUtils.getDrawable(playing
                    ? "yt_fill_experimental_pause_vd_theme_24"
                    : "yt_fill_experimental_play_vd_theme_24"));

            miniSeek.setMax(Math.max(1, duration));
            fullSeek.setMax(Math.max(1, duration));
            if (!userSeeking) {
                miniSeek.setProgress(position, true);
                fullSeek.setProgress(position, true);
            }
            fullCurrentTime.setText(formatMillis(position));
            fullTotalTime.setText(formatMillis(duration));
            updateShuffleRepeatUi();

            List<String> currentQueue = OfflinePlaybackService.getQueue();
            String queueSig = OfflinePlaybackService.getQueueIndex() + ":"
                    + currentQueue.size() + ":"
                    + currentQueue.hashCode() + ":"
                    + OfflinePlaybackService.isShuffleEnabled();
            if (!isInteractingWithQueue && !queueSig.equals(lastQueueSignature)) {
                lastQueueSignature = queueSig;
                refreshFullQueue(trackChanged);
            }
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

        if (fullTitle != null) {
            fullTitle.setText(track.displayTitle());
            fullArtist.setText(track.displayArtist());
        }

        Bitmap bitmap = track.artwork(ROW_ARTWORK_PIXELS);
        if (bitmap != null) {
            miniArtwork.clearColorFilter();
            miniArtwork.setPadding(0, 0, 0, 0);
            miniArtwork.setImageBitmap(bitmap);
            miniArtwork.setScaleType(ImageView.ScaleType.CENTER_CROP);
        }

        if (fullArtwork != null) {
            Bitmap largeBitmap = track.artwork(FULL_ARTWORK_PIXELS);
            if (largeBitmap != null) {
                fullArtwork.clearColorFilter();
                fullArtwork.setPadding(0, 0, 0, 0);
                fullArtwork.setScaleType(ImageView.ScaleType.MATRIX);
                fullArtwork.setImageBitmap(largeBitmap);
                applyTopCropMatrix(fullArtwork, fullArtwork.getWidth(), fullArtwork.getHeight());
            } else {
                fullArtwork.setImageDrawable(
                        ResourceUtils.getDrawable("yt_fill_experimental_play_circle_vd_theme_24"));
                fullArtwork.setColorFilter(foreground());
                fullArtwork.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            }
        }
    }

    private void setButtonEnabled(ImageButton button, boolean enabled) {
        if (button == null) return;
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1f : .35f);
    }
}
