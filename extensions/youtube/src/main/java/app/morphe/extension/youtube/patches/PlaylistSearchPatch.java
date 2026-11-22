/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5200
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import static app.morphe.extension.shared.StringRef.str;

import android.app.Activity;
import android.graphics.drawable.Drawable;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.innertube.utils.AuthUtils;
import app.morphe.extension.shared.theme.ThemeUtils;
import app.morphe.extension.youtube.patches.utils.SearchDialog;
import app.morphe.extension.youtube.patches.utils.SearchResultsSheet;
import app.morphe.extension.youtube.patches.utils.requests.PlaylistSearchRequest;
import app.morphe.extension.youtube.settings.Settings;

/**
 * Adds a search button to the action bar of a playlist page, which searches only that playlist.
 * The results are shown in the same list as {@link ChannelSearchPatch}.
 */
@SuppressWarnings("unused")
public final class PlaylistSearchPatch {

    /**
     * Playlist pages use the playlist id with this prefix as their browse id.
     */
    private static final String PLAYLIST_BROWSE_ID_PREFIX = "VL";

    private static final int MENU_SEARCH_ID = ResourceUtils.getIdIdentifier("menu_search");

    private static final int MENU_ITEM_ID = View.generateViewId();

    /**
     * Browse id of the page the user is on.
     */
    private static String currentBrowseId = "";

    /**
     * Playlist search item of the action bar menu that was created last.
     */
    private static WeakReference<MenuItem> menuItemRef = new WeakReference<>(null);

    private static WeakReference<SearchController> activeControllerRef = new WeakReference<>(null);

    /**
     * Injection point.
     * Called on main thread.
     */
    public static void setBrowseId(@Nullable String browseId) {
        try {
            if (!Settings.PLAYLIST_SEARCH.get()) {
                return;
            }

            currentBrowseId = browseId == null ? "" : browseId;
            updateMenuItem();
        } catch (Exception ex) {
            Logger.printException(() -> "setBrowseId failure", ex);
        }
    }

    /**
     * Injection point.
     * The search feed is not a browse page, and returning to it from a playlist sets no browse id.
     */
    public static void clearBrowseId() {
        setBrowseId(null);
    }

    /**
     * Injection point.
     * Called after the menu of the action bar of a page is created. Only pages with a search item get
     * the playlist search item, and it is shown only while a playlist is open.
     */
    public static void addSearchMenuItem(Menu menu) {
        try {
            if (!Settings.PLAYLIST_SEARCH.get()) {
                return;
            }

            MenuItem searchItem = menu.findItem(MENU_SEARCH_ID);
            if (searchItem == null || menu.findItem(MENU_ITEM_ID) != null) {
                return;
            }

            Drawable icon = ResourceUtils.getDrawable("morphe_playlist_search_button");
            if (icon == null) {
                Logger.printException(() -> "addSearchMenuItem drawable not found");
                return;
            }
            icon.setTint(ThemeUtils.getAppForegroundColor());

            // Placed before the search item of YouTube.
            MenuItem menuItem = menu.add(Menu.NONE, MENU_ITEM_ID, searchItem.getOrder() - 1,
                    str("morphe_playlist_search_hint"));
            menuItem.setIcon(icon);
            menuItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
            menuItem.setOnMenuItemClickListener(item -> {
                showSearchDialog();
                return true;
            });
            menuItemRef = new WeakReference<>(menuItem);
            updateMenuItem();
        } catch (Exception ex) {
            Logger.printException(() -> "addSearchMenuItem failure", ex);
        }
    }

    private static void updateMenuItem() {
        MenuItem menuItem = menuItemRef.get();
        if (menuItem != null) {
            menuItem.setVisible(isPlaylistBrowseId(currentBrowseId));
        }
    }

    private static void showSearchDialog() {
        String browseId = currentBrowseId;
        Activity activity = Utils.getActivity();
        if (!isPlaylistBrowseId(browseId) || activity == null) {
            return;
        }
        String playlistId = browseId.substring(PLAYLIST_BROWSE_ID_PREFIX.length());

        SearchDialog.show(activity, str("morphe_playlist_search_hint"), str("morphe_search_dialog_search"),
                query -> search(activity, playlistId, query));
    }

    private static void search(Activity activity, String playlistId, String query) {
        Logger.printDebug(() -> "Searching playlist: " + playlistId + " for: " + query);

        SearchController previousController = activeControllerRef.get();
        if (previousController != null) {
            previousController.cancel();
            if (previousController.sheet.isShowing()) {
                previousController.sheet.dismiss();
            }
        }

        SearchController controller = new SearchController(activity, playlistId, query);
        activeControllerRef = new WeakReference<>(controller);
        PlaylistSearchRequest.fetch(playlistId, query, AuthUtils.getRequestHeader(),
                controller::isCancelled, controller);
    }

    private static final class SearchController implements PlaylistSearchRequest.Callback {
        private final String playlistId;
        private final SearchResultsSheet sheet;
        private volatile boolean isCancelled;

        private SearchController(Activity activity, String playlistId, String query) {
            this.playlistId = playlistId;
            sheet = new SearchResultsSheet(activity, query, str("morphe_playlist_search_results"),
                    null, this::cancel);
        }

        private boolean isCancelled() {
            return isCancelled || !sheet.isShowing();
        }

        private void cancel() {
            isCancelled = true;
        }

        @Override
        public void onResults(@Nullable PlaylistSearchRequest.Results results, boolean isComplete) {
            Utils.runOnMainThreadNowOrLater(() -> {
                if (isCancelled()) {
                    return;
                }
                if (results == null) {
                    sheet.showState(str("morphe_playlist_search_failed"));
                    return;
                }
                sheet.setScopeName(results.playlistTitle);
                if (results.matches.isEmpty()) {
                    if (isComplete) {
                        sheet.showState(str("morphe_playlist_search_no_results"));
                    }
                    return;
                }
                // The rest of the playlist can still be searched.
                sheet.showVideos(results.matches, playlistId, !isComplete);
            });
        }
    }

    private static boolean isPlaylistBrowseId(String browseId) {
        return browseId.length() > PLAYLIST_BROWSE_ID_PREFIX.length()
                && browseId.startsWith(PLAYLIST_BROWSE_ID_PREFIX);
    }
}
