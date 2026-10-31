/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2528
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.downloads;

import static app.morphe.extension.shared.StringRef.str;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Outline;
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
import android.view.ViewOutlineProvider;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
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

    /** Dimming the foreground gives a readable secondary on any background. */            
    private static final float SECONDARY_DIM = 0.72f;
    
    /** How much the background of the playing row is lifted out of the surrounding one. */           
    private static final float PLAYING_ROW_BRIGHTNESS = 1.35f;
                
    private static final int ROW_CORNER_DP = 8;
                
    private static final int PILL_CORNER_DP = 20;

    /** Rows are 56dp, so a sample of the stored cover is already more than enough. */            
    private static final int ROW_ARTWORK_PIXELS = 256;
                
    private static final int ARTWORK_CORNER_DP = 4;
                
    private static final int MENU_WIDTH_DP = 220;
    private static final int MENU_CORNER_DP = 12;

    /** Icon buttons are square, otherwise their round ripple is drawn as a stretched oval. */            
    private static final int ICON_BUTTON_DP = 48;

    /** Tracks the actual queue sent to OfflinePlaybackService (whether main list or playlist). */
    private static final List<String> activeQueuePaths = new ArrayList<>();

    private File musicRoot;
    private OfflinePlaylistManager playlistManager;
    private List<OfflinePlaylist> createdPlaylists = new ArrayList<>();
    private final List<String> selectedTrackPaths = new ArrayList<>();
    private boolean selectionMode;
    private boolean playlistsExpanded;

    private ImageView miniArtwork;
    private TextView miniTitle;
    private TextView miniArtist;
    private ImageButton miniPlay;
    private ImageButton miniPrevious;
    private ImageButton miniNext;
    private SeekBar miniSeek;
    private LinearLayout miniPlayer;
    private LinearLayout songsList;
    private LinearLayout createdPlaylistsContainer;
    private LinearLayout selectionToolbar;
    private TextView newPlaylistSelectButton;
    private ImageButton playlistsToggleButton;

    private boolean showingPause;

    /**
     * Every downloaded track, read once per refresh instead of on every list or playback update.
     */            
    private List<OfflineTrack> tracks = new ArrayList<>();

    /** Each row, kept so the playing one can be marked without redrawing the list. */            
    private final List<View> rowViews = new ArrayList<>();
                
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

    /** Updates the active playback queue so mini-player skip buttons reference the correct list. */
    static void setActiveQueue(List<String> queue) {
        activeQueuePaths.clear();
        activeQueuePaths.addAll(queue);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle state) {
        musicRoot = new File(getActivity().getExternalFilesDir(Environment.DIRECTORY_MUSIC), "Morphe");
        playlistManager = new OfflinePlaylistManager(musicRoot);
        refreshCreatedPlaylists();

        LinearLayout root = new LinearLayout(getActivity());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(background());
        root.addView(createHeader(), new LinearLayout.LayoutParams(-1, -2));

        ScrollView scroll = new ScrollView(getActivity());
        songsList = new LinearLayout(getActivity());
        songsList.setOrientation(LinearLayout.VERTICAL);
        songsList.setPadding(dp(8), 0, dp(8), dp(12));
        scroll.addView(songsList);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        showTracks();
        renderCreatedPlaylists();

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

    /** Reloads the list of local playlists from storage. */
    private void refreshCreatedPlaylists() {
        createdPlaylists = playlistManager.loadPlaylists();
    }

    /** Toggles the visibility state of the collapsible created playlists section. */
    private void toggleCreatedPlaylistsSection() {
        playlistsExpanded = !playlistsExpanded;
        if (createdPlaylistsContainer != null) {
            createdPlaylistsContainer.setVisibility(playlistsExpanded ? View.VISIBLE : View.GONE);
        }
        renderCreatedPlaylists();
    }

    /** Renders the collapsible created playlists list view. */
    private void renderCreatedPlaylists() {
        if (createdPlaylistsContainer == null) return;
        createdPlaylistsContainer.removeAllViews();
        createdPlaylistsContainer.setVisibility(playlistsExpanded ? View.VISIBLE : View.GONE);
        if (!playlistsExpanded) return;

        // Top row of collapsible section: Title + "+ New playlist" (toggles multi-select mode)
        LinearLayout topBar = new LinearLayout(getActivity());
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(4), dp(6), dp(4), dp(6));

        TextView sectionHeader = text("Created playlists (" + createdPlaylists.size() + ")", 15, foreground());
        sectionHeader.setTypeface(Typeface.DEFAULT_BOLD);
        topBar.addView(sectionHeader, new LinearLayout.LayoutParams(0, -2, 1));

        String btnLabel = selectionMode
                ? "Selected (" + selectedTrackPaths.size() + ")"
                : "+ New playlist";
        newPlaylistSelectButton = pillButton(btnLabel, false);
        newPlaylistSelectButton.setOnClickListener(v -> toggleSelectionMode());
        topBar.addView(newPlaylistSelectButton, new LinearLayout.LayoutParams(-2, -2));

        createdPlaylistsContainer.addView(topBar, new LinearLayout.LayoutParams(-1, -2));

        if (createdPlaylists.isEmpty()) {
            TextView empty = text("No local playlists yet", 13, secondary());
            empty.setPadding(dp(6), dp(8), dp(6), dp(8));
            createdPlaylistsContainer.addView(empty);
        } else {
            ScrollView playlistScroll = new ScrollView(getActivity());
            LinearLayout listInner = new LinearLayout(getActivity());
            listInner.setOrientation(LinearLayout.VERTICAL);

            for (OfflinePlaylist playlist : createdPlaylists) {
                LinearLayout row = new LinearLayout(getActivity());
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(10), dp(8), dp(10), dp(8));
                row.setBackground(rowBackground(false));

                LinearLayout labels = new LinearLayout(getActivity());
                labels.setOrientation(LinearLayout.VERTICAL);

                TextView title = text(playlist.name(), 15, foreground());
                title.setTypeface(Typeface.DEFAULT_BOLD);
                title.setSingleLine(true);

                int count = playlist.trackPaths().size();
                TextView subtitle = text(count + (count == 1 ? " track" : " tracks"), 13, secondary());
                subtitle.setSingleLine(true);

                labels.addView(title);
                labels.addView(subtitle);
                row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));

                ImageButton playBtn = icon("yt_fill_experimental_play_vd_theme_24", "Play playlist");
                playBtn.setOnClickListener(v -> playOfflinePlaylist(playlist));
                row.addView(playBtn, new LinearLayout.LayoutParams(dp(40), dp(40)));

                row.setOnClickListener(v -> openPlaylistPage(playlist));
                listInner.addView(row);
            }

            int maxHeight = createdPlaylists.size() > 4 ? dp(210) : ViewGroup.LayoutParams.WRAP_CONTENT;
            playlistScroll.addView(listInner);
            createdPlaylistsContainer.addView(playlistScroll, new LinearLayout.LayoutParams(-1, maxHeight));
        }

        // Collapse button at the bottom of the Created playlists menu
        LinearLayout bottomCollapseBar = new LinearLayout(getActivity());
        bottomCollapseBar.setGravity(Gravity.CENTER);
        bottomCollapseBar.setPadding(0, dp(6), 0, dp(4));

        TextView collapseBtn = pillButton("Hide playlists ▲", false);
        collapseBtn.setOnClickListener(v -> toggleCreatedPlaylistsSection());
        bottomCollapseBar.addView(collapseBtn, new LinearLayout.LayoutParams(-2, -2));

        createdPlaylistsContainer.addView(bottomCollapseBar, new LinearLayout.LayoutParams(-1, -2));

        View divider = new View(getActivity());
        divider.setBackgroundColor(rippleColor());
        LinearLayout.LayoutParams divParams = new LinearLayout.LayoutParams(-1, dp(1));
        divParams.topMargin = dp(6);
        createdPlaylistsContainer.addView(divider, divParams);
    }

    /** Opens the dedicated LocalPlaylistFragment page for managing a specific playlist. */
    private void openPlaylistPage(OfflinePlaylist playlist) {
        if (getFragmentManager() == null) return;
        int containerId = android.R.id.content;
        if (getView() != null && getView().getParent() instanceof ViewGroup parent
                && parent.getId() != View.NO_ID) {
            containerId = parent.getId();
        }

        LocalPlaylistFragment fragment = LocalPlaylistFragment.newInstance(playlist.id(), () -> {
            refreshCreatedPlaylists();
            renderCreatedPlaylists();
        });

        getFragmentManager()
                .beginTransaction()
                .add(containerId, fragment)
                .addToBackStack(null)
                .commit();
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
        header.setPadding(dp(16), dp(8), dp(16), dp(6));

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
        line.setPadding(0, dp(8), 0, 0);

        summary = text("", 13, secondary());
        line.addView(summary, new LinearLayout.LayoutParams(0, -2, 1));

        ImageButton search = icon(BaseSearchViewController.getSearchIconDrawable(),
                str("morphe_music_downloads_search_hint"));
        search.setOnClickListener(v -> toggleSearch());
        line.addView(search, new LinearLayout.LayoutParams(dp(40), dp(40)));

        ImageButton sort = icon("yt_outline_experimental_sort_vd_theme_24",
                str("morphe_music_downloads_sort"));
        sort.setOnClickListener(v -> showSortMenu(sort));
        line.addView(sort, new LinearLayout.LayoutParams(dp(40), dp(40)));

        // Playlist icon button opens/collapses the Created Playlists section below this menu
        playlistsToggleButton = icon("yt_outline_experimental_playlist_vd_theme_24",
                "Toggle created playlists");
        playlistsToggleButton.setOnClickListener(v -> toggleCreatedPlaylistsSection());
        line.addView(playlistsToggleButton, new LinearLayout.LayoutParams(dp(40), dp(40)));

        ImageButton deleteAll = icon("yt_outline_experimental_trash_can_vd_theme_24",
                str("morphe_music_downloads_delete_all"));
        deleteAll.setOnClickListener(v -> confirmDeleteAll());
        line.addView(deleteAll, new LinearLayout.LayoutParams(dp(40), dp(40)));

        header.addView(line, new LinearLayout.LayoutParams(-1, -2));

        // Selection Toolbar (shown when selectionMode == true)
        selectionToolbar = new LinearLayout(getActivity());
        selectionToolbar.setGravity(Gravity.CENTER_VERTICAL);
        selectionToolbar.setPadding(0, dp(8), 0, dp(4));
        selectionToolbar.setVisibility(View.GONE);

        TextView createFromSelectedBtn = pillButton("Create playlist", false);
        createFromSelectedBtn.setOnClickListener(v -> createPlaylistFromSelectedDialog());
        selectionToolbar.addView(createFromSelectedBtn, new LinearLayout.LayoutParams(-2, -2));

        View space1 = new View(getActivity());
        selectionToolbar.addView(space1, new LinearLayout.LayoutParams(dp(8), dp(1)));

        TextView addSelectedBtn = pillButton("Add to playlist", false);
        addSelectedBtn.setOnClickListener(v -> addSelectedToPlaylist());
        selectionToolbar.addView(addSelectedBtn, new LinearLayout.LayoutParams(-2, -2));

        View space2 = new View(getActivity());
        selectionToolbar.addView(space2, new LinearLayout.LayoutParams(dp(8), dp(1)));

        TextView clearSelectionBtn = pillButton("Clear selection", false);
        clearSelectionBtn.setTextColor(secondary());
        clearSelectionBtn.setOnClickListener(v -> clearSelection());
        selectionToolbar.addView(clearSelectionBtn, new LinearLayout.LayoutParams(-2, -2));

        header.addView(selectionToolbar, new LinearLayout.LayoutParams(-1, -2));

        // Collapsible "Created playlists" container directly below the toolbar menu
        createdPlaylistsContainer = new LinearLayout(getActivity());
        createdPlaylistsContainer.setOrientation(LinearLayout.VERTICAL);
        createdPlaylistsContainer.setPadding(0, dp(6), 0, dp(4));
        createdPlaylistsContainer.setVisibility(View.GONE);
        header.addView(createdPlaylistsContainer, new LinearLayout.LayoutParams(-1, -2));

        return header;
    }

    /** Creates a styled pill button matching the app's UI guidelines. */
    private TextView pillButton(String label, boolean primary) {
        TextView btn = text(label, 13, primary ? background() : foreground());
        btn.setPadding(dp(14), dp(6), dp(14), dp(6));
        btn.setBackground(createPillBackground(primary));
        return btn;
    }

    /** Creates a rounded ripple background for pill buttons that adapts to any theme background. */
    private Drawable createPillBackground(boolean primary) {
        final int fg = foreground();
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(PILL_CORNER_DP));
        if (primary) {
            shape.setColor(fg);
        } else {
            shape.setColor(Color.argb(26, Color.red(fg), Color.green(fg), Color.blue(fg)));
            shape.setStroke(dp(1), Color.argb(65, Color.red(fg), Color.green(fg), Color.blue(fg)));
        }
        return new RippleDrawable(ColorStateList.valueOf(rippleColor()), shape,
                CustomDialog.createRoundedBackground(PILL_CORNER_DP, Color.WHITE));
    }

    /** Toggles multi-select mode for batch track operations. */
    private void toggleSelectionMode() {
        selectionMode = !selectionMode;
        selectedTrackPaths.clear();
        updateSelectionUi();
    }

    /** Clears all selected tracks and exits selection mode. */
    private void clearSelection() {
        selectedTrackPaths.clear();
        selectionMode = false;
        updateSelectionUi();
    }

    /** Refreshes UI states when entering or exiting multi-select mode. */
    private void updateSelectionUi() {
        if (newPlaylistSelectButton != null) {
            newPlaylistSelectButton.setText(selectionMode
                    ? "Selected (" + selectedTrackPaths.size() + ")"
                    : "+ New playlist");
        }
        if (selectionToolbar != null) {
            selectionToolbar.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
        }
        renderTracksWithSelectionState();
        refreshRowsSelectionState();
    }

    /** Shows or hides checkboxes on all song rows depending on multi-select state. */
    private void renderTracksWithSelectionState() {
        for (int i = 0; i < songsList.getChildCount(); i++) {
            View child = songsList.getChildAt(i);
            if (child instanceof LinearLayout row) {
                for (int j = 0; j < row.getChildCount(); j++) {
                    View childView = row.getChildAt(j);
                    if (childView instanceof CheckBox box) {
                        box.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
                    }
                }
            }
        }
    }

    /** Toggles individual track selection state. */
    private void toggleSelected(OfflineTrack track) {
        String path = track.audioFile().getAbsolutePath();
        if (!selectedTrackPaths.remove(path)) {
            selectedTrackPaths.add(path);
        }
        if (newPlaylistSelectButton != null && selectionMode) {
            newPlaylistSelectButton.setText("Selected (" + selectedTrackPaths.size() + ")");
        }
        refreshRowsSelectionState();
    }

    /** Updates checkbox checked states across visible rows. */
    private void refreshRowsSelectionState() {
        for (int i = 0; i < songsList.getChildCount(); i++) {
            View row = songsList.getChildAt(i);
            if (!(row instanceof LinearLayout layout)) continue;
            for (int j = 0; j < layout.getChildCount(); j++) {
                View child = layout.getChildAt(j);
                if (child instanceof CheckBox box) {
                    String tag = (String) box.getTag();
                    if (tag != null) {
                        box.setChecked(selectedTrackPaths.contains(tag));
                    }
                }
            }
        }
    }

    /** Triggers adding selected tracks to an existing or new local playlist. */
    private void addSelectedToPlaylist() {
        if (selectedTrackPaths.isEmpty()) {
            Utils.showToastShort("Select songs first");
            return;
        }
        showAddTracksToPlaylistDialog(new ArrayList<>(selectedTrackPaths), true);
    }

    /** Displays a dialog allowing the user to pick a target playlist to add songs into. */
    private void showAddTracksToPlaylistDialog(List<String> pathsToAdd, boolean exitSelectOnSave) {
        if (createdPlaylists.isEmpty()) {
            showCreatePlaylistDialog("", pathsToAdd, exitSelectOnSave);
            return;
        }

        Pair<Dialog, LinearLayout> dialogPair = CustomDialog.create(
                getActivity(),
                "Add to playlist",
                null,
                null,
                "New playlist",
                () -> showCreatePlaylistDialog("", pathsToAdd, exitSelectOnSave),
                () -> {},
                null,
                null,
                true
        );

        Dialog dialog = dialogPair.first;
        LinearLayout dialogRoot = dialogPair.second;

        ScrollView scroll = new ScrollView(getActivity());
        LinearLayout listContainer = new LinearLayout(getActivity());
        listContainer.setOrientation(LinearLayout.VERTICAL);
        listContainer.setPadding(0, dp(4), 0, dp(8));

        for (OfflinePlaylist target : createdPlaylists) {
            LinearLayout itemRow = new LinearLayout(getActivity());
            itemRow.setGravity(Gravity.CENTER_VERTICAL);
            itemRow.setPadding(dp(14), dp(12), dp(14), dp(12));
            itemRow.setBackground(rowBackground(false));

            TextView nameView = text(target.name(), 15, foreground());
            nameView.setTypeface(Typeface.DEFAULT_BOLD);
            nameView.setSingleLine(true);
            itemRow.addView(nameView, new LinearLayout.LayoutParams(0, -2, 1));

            int count = target.trackPaths().size();
            TextView countView = text(count + (count == 1 ? " track" : " tracks"), 13, secondary());
            itemRow.addView(countView, new LinearLayout.LayoutParams(-2, -2));

            itemRow.setOnClickListener(v -> {
                try {
                    playlistManager.addTrackPathsToPlaylist(target.id(), pathsToAdd);
                    refreshCreatedPlaylists();
                    renderCreatedPlaylists();
                    if (exitSelectOnSave) {
                        clearSelection();
                    }
                    dialog.dismiss();
                    Utils.showToastShort("Added to " + target.name());
                } catch (Exception ex) {
                    Utils.showToastShort("Could not add songs");
                }
            });
            listContainer.addView(itemRow);
        }

        scroll.addView(listContainer);
        int maxHeight = createdPlaylists.size() > 5 ? dp(260) : ViewGroup.LayoutParams.WRAP_CONTENT;
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(-1, maxHeight);
        scrollParams.bottomMargin = dp(12);

        int insertIndex = Math.max(1, dialogRoot.getChildCount() - 1);
        dialogRoot.addView(scroll, insertIndex, scrollParams);

        dialog.show();
    }

    private void createPlaylistFromSelectedDialog() {
        if (selectedTrackPaths.isEmpty()) {
            Utils.showToastShort("Select songs first");
            return;
        }
        showCreatePlaylistDialog("", new ArrayList<>(selectedTrackPaths), true);
    }

    /** Opens a dialog to create a new local playlist with the given tracks. */
    private void showCreatePlaylistDialog(String defaultName, List<String> pathsToSave, boolean exitSelectOnSave) {
        final EditText input = new EditText(getActivity());
        input.setHint("Playlist name");
        input.setHintTextColor(secondary());
        input.setTextColor(foreground());
        input.setText(defaultName);
        input.setSingleLine(true);

        CustomDialog.create(
                getActivity(),
                "Create playlist",
                null,
                input,
                "Create",
                () -> {
                    String title = input.getText() == null ? "" : input.getText().toString().trim();
                    if (title.isEmpty()) {
                        Utils.showToastShort("Playlist name cannot be empty");
                        return;
                    }

                    OfflinePlaylist playlist = playlistManager.createNewPlaylistFromPaths(title, pathsToSave);
                    try {
                        playlistManager.savePlaylist(playlist);
                        refreshCreatedPlaylists();
                        playlistsExpanded = true;
                        if (exitSelectOnSave) {
                            clearSelection();
                        }
                        renderCreatedPlaylists();
                        Utils.showToastShort("Playlist created");
                    } catch (Exception ex) {
                        Utils.showToastShort("Could not create playlist");
                    }
                },
                () -> {},
                null,
                null,
                false
        ).first.show();
    }

    /** Opens the search field, or closes it and drops the query. */
    private void toggleSearch() {
        final boolean opening = searchBar.getVisibility() != View.VISIBLE;
        InputMethodManager keyboard = (InputMethodManager)
                getActivity().getSystemService(Context.INPUT_METHOD_SERVICE);

        if (opening) {
            searchBar.setVisibility(View.VISIBLE);
            slideSearchBar(dp(SEARCH_BAR_HEIGHT_DP));
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
            TextView empty = text(str(tracks.isEmpty()
                    ? "morphe_music_downloads_empty"
                    : "morphe_music_downloads_nothing_found"), 16, secondary());
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(80), 0, 0);
            songsList.addView(empty);
            return;
        }
        markPlayingRow();
    }

    private View songRow(OfflineTrack track) {
        LinearLayout row = new LinearLayout(getActivity());
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(7), dp(8), dp(7));
        rowViews.add(row);

        String trackPath = track.audioFile().getAbsolutePath();
        CheckBox checkBox = new CheckBox(getActivity());
        checkBox.setTag(trackPath);
        checkBox.setButtonTintList(ColorStateList.valueOf(foreground()));
        checkBox.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
        checkBox.setChecked(selectedTrackPaths.contains(trackPath));
        checkBox.setOnClickListener(v -> toggleSelected(track));
        row.addView(checkBox, new LinearLayout.LayoutParams(dp(40), dp(40)));

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

        ImageButton menu = icon("yt_outline_experimental_overflow_vertical_vd_theme_24",
                str("morphe_music_downloads_actions_for", track.displayTitle()));
        menu.setOnClickListener(v -> showTrackMenu(menu, track));
        row.addView(menu, new LinearLayout.LayoutParams(dp(ICON_BUTTON_DP), dp(ICON_BUTTON_DP)));

        row.setOnClickListener(v -> {
            if (selectionMode) {
                toggleSelected(track);
                return;
            }
            play(track);
        });
        return row;
    }

    /** Lifts the card of the track being played out of the background. */
    private void markPlayingRow() {
        int playingIndex = displayedTrackPaths.indexOf(playingPath);
        for (int i = 0; i < rowViews.size(); i++) {
            rowViews.get(i).setBackground(rowBackground(i == playingIndex));
        }
    }

    /** The ripple is masked to the card, so it does not spill into the neighboring rows. */
    private Drawable rowBackground(boolean playing) {
        Drawable card = playing
                ? CustomDialog.createRoundedBackground(ROW_CORNER_DP, Utils.adjustColorBrightness(
                        ThemeUtils.getAppBackgroundColor(), PLAYING_ROW_BRIGHTNESS))
                : null;
        return new RippleDrawable(ColorStateList.valueOf(rippleColor()), card,
                CustomDialog.createRoundedBackground(ROW_CORNER_DP, Color.WHITE));
    }

    private static int rippleColor() {
        final int foreground = foreground();
        return Color.argb(60, Color.red(foreground),
                Color.green(foreground), Color.blue(foreground));
    }

    private LinearLayout createStockMiniPlayer() {
        LinearLayout outer = new LinearLayout(getActivity());
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setBackgroundColor(ThemeUtils.getDialogBackgroundColor());

        // The surface color of a theme can sit very close to its background, so the bar is
        // separated by a rule rather than by the fill alone.
        View divider = new View(getActivity());
        divider.setBackgroundColor(rippleColor());
        outer.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
        // Lifts the bar off the gesture area, so the seek bar is not on the screen edge.
        outer.setPadding(0, 0, 0, dp(12));

        LinearLayout line = new LinearLayout(getActivity());
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(dp(16), 0, 0, 0);

        miniArtwork = artworkView(null);
        line.addView(miniArtwork, new LinearLayout.LayoutParams(dp(48), dp(48)));

        LinearLayout labels = new LinearLayout(getActivity());
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setGravity(Gravity.CENTER_VERTICAL);
        labels.setPadding(dp(16), 0, dp(4), 0);

        miniTitle = text("", 14, foreground());
        miniTitle.setSingleLine(true);
        miniTitle.setTypeface(Typeface.DEFAULT_BOLD);

        miniArtist = text("", 14, secondary());
        miniArtist.setSingleLine(true);

        labels.addView(miniTitle);
        labels.addView(miniArtist);
        line.addView(labels, new LinearLayout.LayoutParams(0, dp(56), 1));

        miniPrevious = icon("yt_fill_experimental_skip_previous_vd_theme_24",
                str("morphe_music_downloads_previous"));
        miniPrevious.setOnClickListener(v -> OfflinePlaybackService.skipPrevious(getActivity()));
        line.addView(miniPrevious, new LinearLayout.LayoutParams(dp(ICON_BUTTON_DP), dp(ICON_BUTTON_DP)));

        miniPlay = icon("yt_fill_experimental_play_vd_theme_24",
                str("morphe_music_downloads_play_pause"));
        // The morph drawable of the app is 48dp, so whatever the button shows is scaled down to
        // the 24dp of the icons beside it.
        miniPlay.setScaleType(ImageView.ScaleType.FIT_CENTER);
        miniPlay.setPadding(dp(12), dp(12), dp(12), dp(12));
        miniPlay.setOnClickListener(v -> OfflinePlaybackService.toggle(getActivity()));
        line.addView(miniPlay, new LinearLayout.LayoutParams(dp(ICON_BUTTON_DP), dp(ICON_BUTTON_DP)));

        miniNext = icon("yt_fill_experimental_skip_next_vd_theme_24",
                str("morphe_music_downloads_next"));
        miniNext.setOnClickListener(v -> OfflinePlaybackService.skipNext(getActivity()));
        line.addView(miniNext, new LinearLayout.LayoutParams(dp(ICON_BUTTON_DP), dp(ICON_BUTTON_DP)));

        outer.addView(line, new LinearLayout.LayoutParams(-1, dp(60)));
        outer.addView(createSeekBar(), new LinearLayout.LayoutParams(-1, dp(SEEK_BAR_TOUCH_DP)));
        return outer;
    }

    private SeekBar createSeekBar() {
        miniSeek = new SeekBar(getActivity());
        // The bar keeps the gutters of the list, so it reads as a control rather than an edge.
        miniSeek.setPadding(dp(16), 0, dp(16), 0);
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
                bar.setThumbOffset(dp(SEEK_THUMB_DP) / 2);
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
        thumb.setIntrinsicWidth(dp(SEEK_THUMB_DP));
        thumb.setIntrinsicHeight(dp(SEEK_THUMB_DP));
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
        final int inset = (dp(SEEK_BAR_TOUCH_DP) - dp(SEEK_BAR_HEIGHT_DP)) / 2;
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
                "Add to local playlist",
                str("morphe_music_downloads_delete_track"),
        };
        showMenu(anchor, items, -1, position -> {
            if (position == 1) {
                showAddTracksToPlaylistDialog(List.of(track.audioFile().getAbsolutePath()), false);
                return;
            }
            if (position == 2) {
                confirmDelete(track);
                return;
            }
            play(track);
        });
    }

    /** Starts queue playback for an entire local playlist. */
    private void playOfflinePlaylist(OfflinePlaylist playlist) {
        ArrayList<String> queue = new ArrayList<>();
        for (String path : playlist.trackPaths()) {
            if (new File(path).isFile()) {
                queue.add(path);
            }
        }

        if (queue.isEmpty()) {
            Utils.showToastShort("No local tracks available in this playlist");
            return;
        }

        setActiveQueue(queue);

        String firstPath = queue.get(0);
        OfflineTrack firstTrack = findByPath(firstPath);
        if (firstTrack == null) {
            firstTrack = OfflineTrack.load(new File(firstPath));
        }

        Intent intent = new Intent(getActivity(), OfflinePlaybackService.class)
                .setAction(OfflinePlaybackService.ACTION_PLAY_FILE)
                .putExtra(OfflinePlaybackService.EXTRA_PATH, firstTrack.audioFile().getAbsolutePath())
                .putExtra(OfflinePlaybackService.EXTRA_TITLE, firstTrack.displayTitle())
                .putExtra(OfflinePlaybackService.EXTRA_ARTIST, firstTrack.displayArtist())
                .putExtra(OfflinePlaybackService.EXTRA_ARTWORK_PATH, firstTrack.artworkFile().getAbsolutePath())
                .putStringArrayListExtra(OfflinePlaybackService.EXTRA_QUEUE, queue)
                .putExtra(OfflinePlaybackService.EXTRA_QUEUE_INDEX, 0);
        getActivity().startForegroundService(intent);
        applyTrackVisuals(firstTrack);
    }

    /**
     * @param selected Index drawn as the active choice, or -1 when the items are plain actions.
     */
    private void showMenu(View anchor, String[] items, int selected, IntConsumer onPick) {
        ListPopupWindow popup = new ListPopupWindow(getActivity());
        popup.setAnchorView(anchor);
        popup.setDropDownGravity(Gravity.END);
        popup.setWidth(dp(MENU_WIDTH_DP));
        popup.setModal(true);
        popup.setBackgroundDrawable(menuBackground());
        // The list draws its own square selector, which would bleed past the rounded background.
        popup.setListSelector(new RippleDrawable(ColorStateList.valueOf(rippleColor()), null,
                CustomDialog.createRoundedBackground(MENU_CORNER_DP, Color.WHITE)));
        popup.setAdapter(new ArrayAdapter<>(getActivity(), 0, items) {
            @NonNull
            @Override
            public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                TextView view = text(getItem(position), 15,
                        selected < 0 || position == selected ? foreground() : secondary());
                view.setPadding(dp(20), dp(14), dp(20), dp(14));
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
        background.setCornerRadius(dp(MENU_CORNER_DP));
        background.setStroke(dp(1), rippleColor());
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
        showTracks();
        refreshCreatedPlaylists();
        renderCreatedPlaylists();
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
        showTracks();
        refreshCreatedPlaylists();
        renderCreatedPlaylists();
        Utils.showToastShort(str("morphe_music_downloads_track_deleted"));
    }

    private void play(OfflineTrack track) {
        try {
            ArrayList<String> queue = new ArrayList<>(displayedTrackPaths);
            setActiveQueue(queue);
            final int queueIndex = Math.max(0, queue.indexOf(track.audioFile().getAbsolutePath()));

            Intent intent = new Intent(getActivity(), OfflinePlaybackService.class)
                    .setAction(OfflinePlaybackService.ACTION_PLAY_FILE)
                    .putExtra(OfflinePlaybackService.EXTRA_PATH, track.audioFile().getAbsolutePath())
                    .putExtra(OfflinePlaybackService.EXTRA_TITLE, track.displayTitle())
                    .putExtra(OfflinePlaybackService.EXTRA_ARTIST, track.displayArtist())
                    .putExtra(OfflinePlaybackService.EXTRA_ARTWORK_PATH,
                            track.artworkFile().getAbsolutePath())
                    .putStringArrayListExtra(OfflinePlaybackService.EXTRA_QUEUE, queue)
                    .putExtra(OfflinePlaybackService.EXTRA_QUEUE_INDEX, queueIndex);
            getActivity().startForegroundService(intent);

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
                if (track == null && new File(path).isFile()) {
                    track = OfflineTrack.load(new File(path));
                }
                if (track != null) applyTrackVisuals(track);
                else miniTitle.setText(title);

                markPlayingRow();
            }

            // Always evaluate Previous/Next against the active playback queue (playlist or downloads list)
            List<String> currentQueue = activeQueuePaths.isEmpty() ? displayedTrackPaths : activeQueuePaths;
            int index = currentQueue.indexOf(path);
            setButtonEnabled(miniPrevious, index > 0);
            setButtonEnabled(miniNext, index >= 0 && index + 1 < currentQueue.size());

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

    private ImageView artworkView(@Nullable Bitmap bitmap) {
        ImageView view = new ImageView(getActivity());
        view.setBackgroundColor(artworkPlaceholder());
        roundCorners(view);

        if (bitmap != null) {
            view.setImageBitmap(bitmap);
            view.setScaleType(ImageView.ScaleType.CENTER_CROP);
        } else {
            view.setImageDrawable(
                    ResourceUtils.getDrawable("yt_fill_experimental_play_circle_vd_theme_24"));
            view.setColorFilter(foreground());
            view.setPadding(dp(16), dp(16), dp(16), dp(16));
        }
        return view;
    }

    /** Matches the softly rounded covers the app uses on the home feed. */
    private void roundCorners(View view) {
        final float radius = dp(ARTWORK_CORNER_DP);
        view.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View outlined, Outline outline) {
                outline.setRoundRect(0, 0, outlined.getWidth(), outlined.getHeight(), radius);
            }
        });
        view.setClipToOutline(true);
    }

    private TextView text(String value, int sp, int color) {
        TextView view = new TextView(getActivity());
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private ImageButton icon(String drawable, String description) {
        ImageButton button = icon(ResourceUtils.getDrawable(drawable), description);
        // Icons of the app carry no color of their own.
        button.setColorFilter(foreground());
        return button;
    }

    /** For drawables that already carry the colors of Morphe. */
    private ImageButton icon(Drawable drawable, String description) {
        ImageButton button = new ImageButton(getActivity());
        button.setImageDrawable(drawable);
        button.setContentDescription(description);
        button.setScaleType(ImageView.ScaleType.CENTER);
        button.setPadding(dp(10), dp(10), dp(10), dp(10));

        button.setBackground(new RippleDrawable(ColorStateList.valueOf(rippleColor()),
                null, new ShapeDrawable(new OvalShape())));
        return button;
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

    private static int artworkPlaceholder() {
        return Utils.adjustColorBrightness(foreground(), 0.2f);
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
