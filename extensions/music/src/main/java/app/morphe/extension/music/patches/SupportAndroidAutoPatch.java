/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2489
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches;

import android.net.Uri;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaDescriptionCompat;

import androidx.annotation.GuardedBy;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;

/**
 * Adds YT Music support in Android Auto by intercepting requests for the Playlists folder,
 * loading playlists from the phone Library.
 *
 * <p>During service initialization, {@link #setPhoneBrowseClient} saves the YTM object that requests
 * phone Library and playlist data. {@link #rememberPlaylistsTitleMatch} identifies Playlists in
 * Android Auto's Library by its translated title.
 *
 * <p>When Android Auto opens Playlists, {@link #handleAndroidAutoPlaylists} requests the phone Library.
 * {@link #requestLibraryPage} collects the first Library page's playlists with their titles and artwork.
 * {@link #deliverAndroidAutoPlaylists} returns them when loading finishes.
 *
 * <p>{@link #requestEachPlaylist} reads each playlist's Play button before returning the list.
 * Android Auto sends the selected item's media ID to YTM for playback.
 */
@SuppressWarnings("unused")
public final class SupportAndroidAutoPatch {
    private static final String PHONE_LIBRARY_BROWSE_ID = "FEmusic_library_landing";
    private static final String EPISODES_FOR_LATER_BROWSE_ID = "VLSE";
    private static final String PLAYLISTS_TITLE_RESOURCE_NAME = "library_playlists_shelf_title";
    private static final Executor BACKGROUND_EXECUTOR = Utils::runOnBackgroundThread;
    // A user's playlist can also be named "Playlists"; do not use these title matches for playback.
    private static final Set<String> playlistsTitleMatchMediaIds =
            ConcurrentHashMap.newKeySet();

    /** YTM's object for sending phone Library and playlist requests, reused to supply Android Auto. */
    public interface PhoneBrowseClient {
        @NonNull ListenableFuture<PhoneBrowseResponse> patch_requestBrowse(
                @NonNull String browseId, @NonNull Executor executor);
    }

    /**
     * Data returned by a request for the phone Library or a playlist's contents.
     * The first Library response and playlist contents use TabRenderer data, then sections containing the items.
     * TabRenderer describes the phone page, not Android Auto's Home/Library/Podcasts tabs.
     */
    public interface PhoneBrowseResponse {
        // Wrappers for the TabRenderer data containing the first Library result or playlist contents.
        @NonNull Iterable<PhoneBrowseTab> patch_getTabs();
        // Command from the Play button above the playlist's songs, encoded as an Android Auto media ID.
        @Nullable String patch_getPlaylistPlayButtonMediaId();
    }

    /** YTM's object for extracting sections from TabRenderer data in a phone Library or playlist response. */
    public interface PhoneBrowseTab {
        @Nullable SectionList patch_getSectionList();
    }

    /** Groups of Library items in a phone response. */
    public interface SectionList {
        @NonNull Iterable<?> patch_getContents();
    }

    /** Items returned by the phone Library. */
    public interface GridRenderer {
        // Includes artists and podcasts as well as playlists; filter before returning playlists to Android Auto.
        @NonNull Iterable<?> patch_getItems();
    }

    /** A request from Android Auto to load content, such as its main tabs, Playlists, or a podcast list. */
    public interface AndroidAutoBrowseRequest {
        @Nullable String patch_getRequestedMediaId();
        // YTM may remove items to keep the returned list within its byte limit.
        void patch_deliverAndroidAutoItems(
                @NonNull List<MediaBrowserCompat.MediaItem> androidAutoItems);
    }

    /** YTM's item type for Library content, playlist songs, and the "Add a song" button. */
    public interface PhoneBrowseItem {
        /**
         * Returns a playlist page ID, or null if none is found or the item's commands identify different playlists.
         * A command that does not open a page makes YTM's converter throw;
         * {@link SupportAndroidAutoPatch#collectPlaylistsFromGrid} skips that item.
         */
        @Nullable String patch_getPlaylistBrowseId();
        @Nullable Uri patch_getArtworkUri();
        @Nullable CharSequence patch_getTitle();
        @Nullable CharSequence patch_getSubtitle();
    }

    @Nullable
    private static volatile PhoneBrowseClient phoneBrowseClient;

    private SupportAndroidAutoPatch() {
    }

    // Capture YTM's Library request methods and identify the Playlists folder

    /**
     * Injection point. Save the object MusicBrowserService uses to request the phone Library and playlist contents.
     */
    public static synchronized void setPhoneBrowseClient(@NonNull PhoneBrowseClient client) {
        phoneBrowseClient = client;
        Logger.printDebug(() -> "Ready to request phone Library and playlist contents: " +
                client.getClass().getName());
    }

    /**
     * Injection point. Identify the Playlists folder by its translated title; its ID varies.
     */
    public static void rememberPlaylistsTitleMatch(
            @Nullable String androidAutoMediaId, @Nullable CharSequence title) {
        if (title == null || !ResourceUtils.getString(PLAYLISTS_TITLE_RESOURCE_NAME)
                .contentEquals(title)) return;
        if (androidAutoMediaId != null) playlistsTitleMatchMediaIds.add(androidAutoMediaId);
    }

    // Intercept Android Auto requests for the Playlists folder

    /**
     * Injection point. Load playlists when Android Auto opens the Playlists folder.
     * YTM has already called detach() on Android Auto's result object, so the list can be sent after this method returns.
     *
     * <p>{@link #requestLibraryPage} loads the Library; a failed request returns an empty list.
     *
     * @return true once this patch accepts the request, even while loading;
     *         false to let YTM handle the request.
     */
    public static boolean handleAndroidAutoPlaylists(
            @NonNull AndroidAutoBrowseRequest androidAutoRequest) {
        try {
            PhoneBrowseClient browseClient = phoneBrowseClient;
            if (browseClient == null) return false;
            String requestedMediaId = androidAutoRequest.patch_getRequestedMediaId();
            if (requestedMediaId == null) return false;
            if (!playlistsTitleMatchMediaIds.contains(requestedMediaId)) return false;
            PlaylistsFolderLoad load = new PlaylistsFolderLoad(browseClient);
            try {
                requestLibraryPage(androidAutoRequest, load);
            } catch (RuntimeException ex) {
                // Only this patch should answer the request, including after failure.
                Logger.printException(() -> "Could not request YTM Library", ex);
                synchronized (load) {
                    load.libraryPlaylists.clear();
                }
                deliverAndroidAutoPlaylists(androidAutoRequest, load, "failed");
            }
            return true;
        } catch (RuntimeException ex) {
            Logger.printException(() -> "Could not handle Android Auto Playlists request", ex);
            return false;
        }
    }

    // Playlist loading

    /**
     * Reads the first Library response and requests each playlist's playback command.
     */
    private static void requestLibraryPage(
            AndroidAutoBrowseRequest androidAutoRequest, PlaylistsFolderLoad load) {
        ListenableFuture<PhoneBrowseResponse> libraryResponseFuture = load.phoneBrowseClient.patch_requestBrowse(
                PHONE_LIBRARY_BROWSE_ID, BACKGROUND_EXECUTOR);
        libraryResponseFuture.addListener(() -> {
            synchronized (load) {
                if (load.deliveryPreparationStarted) return;
            }
            try {
                PhoneBrowseResponse libraryResponse = libraryResponseFuture.get();
                appendInitialLibraryPlaylists(libraryResponse, load);
                requestEachPlaylist(androidAutoRequest, load);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                Logger.printException(() -> "YTM Library request interrupted", ex);
                synchronized (load) {
                    load.libraryPlaylists.clear();
                }
                deliverAndroidAutoPlaylists(androidAutoRequest, load, "interrupted");
            } catch (ExecutionException | RuntimeException ex) {
                Logger.printException(() -> "YTM Library request failed", ex);
                synchronized (load) {
                    load.libraryPlaylists.clear();
                }
                deliverAndroidAutoPlaylists(androidAutoRequest, load, "failed");
            }
        }, BACKGROUND_EXECUTOR);
    }

    private static void appendInitialLibraryPlaylists(
            PhoneBrowseResponse libraryResponse, PlaylistsFolderLoad load) {
        for (PhoneBrowseTab tab : libraryResponse.patch_getTabs()) {
            SectionList sectionList = tab.patch_getSectionList();
            if (sectionList == null) continue;
            for (Object sectionContent : sectionList.patch_getContents()) {
                if (!(sectionContent instanceof GridRenderer)) continue;
                GridRenderer gridRenderer = (GridRenderer) sectionContent;
                collectPlaylistsFromGrid(gridRenderer, load);
            }
        }
        synchronized (load) {
            Logger.printDebug(() -> "Found playlists in phone Library: " +
                    load.libraryPlaylists.size());
        }
    }

    /**
     * {@link #addLibraryPlaylist} keeps playlist IDs, titles, and artwork from each Library item.
     * Skip an unreadable item without discarding the remaining playlists.
     */
    private static void collectPlaylistsFromGrid(
            GridRenderer gridRenderer, PlaylistsFolderLoad load) {
        for (Object libraryItem : gridRenderer.patch_getItems()) {
            if (!(libraryItem instanceof PhoneBrowseItem)) continue;
            try {
                addLibraryPlaylist((PhoneBrowseItem) libraryItem, load);
            } catch (RuntimeException ex) {
                Logger.printException(() -> "Could not read a phone Library item", ex);
            }
        }
    }

    // Playlist titles and artwork

    /** Injection point. Exclude artists and shows; skip commands that identify different playlists. */
    @Nullable
    public static String resolvePlaylistBrowseId(
            @Nullable String singleTapBrowseId, @Nullable String doubleTapBrowseId) {
        if (singleTapBrowseId != null && !singleTapBrowseId.startsWith("VL")) singleTapBrowseId = null;
        if (doubleTapBrowseId != null && !doubleTapBrowseId.startsWith("VL")) doubleTapBrowseId = null;
        if (singleTapBrowseId == null) return doubleTapBrowseId;
        if (doubleTapBrowseId == null || singleTapBrowseId.equals(doubleTapBrowseId)) return singleTapBrowseId;
        return null;
    }

    private static void addLibraryPlaylist(
            PhoneBrowseItem libraryItem, PlaylistsFolderLoad load) {
        String playlistBrowseId = libraryItem.patch_getPlaylistBrowseId();
        if (playlistBrowseId == null) return;
        // Episodes for Later (VLSE) has no Play button.
        if (EPISODES_FOR_LATER_BROWSE_ID.equals(playlistBrowseId)) return;

        CharSequence titleText = libraryItem.patch_getTitle();
        String title = titleText == null ? "" : titleText.toString();
        if (title.isEmpty()) return;
        // Keep the playlist available if its subtitle or artwork cannot be read.
        LibraryPlaylist playlist = new LibraryPlaylist(
                playlistBrowseId,
                title,
                subtitleOrEmpty(libraryItem),
                artworkUriOrNull(libraryItem));
        synchronized (load) {
            if (load.deliveryPreparationStarted || !load.seenPlaylistBrowseIds.add(playlistBrowseId))
                return;
            load.libraryPlaylists.add(playlist);
        }
    }

    private static String subtitleOrEmpty(PhoneBrowseItem libraryItem) {
        try {
            CharSequence subtitle = libraryItem.patch_getSubtitle();
            return subtitle == null ? "" : subtitle.toString();
        } catch (RuntimeException ex) {
            Logger.printInfo(() -> "Could not read playlist subtitle; leaving it blank", ex);
            return "";
        }
    }

    private static Uri artworkUriOrNull(PhoneBrowseItem libraryItem) {
        try {
            return libraryItem.patch_getArtworkUri();
        } catch (RuntimeException ex) {
            Logger.printInfo(() -> "Could not read playlist artwork; leaving it unset", ex);
            return null;
        }
    }

    // Return playlists to Android Auto

    /** Requests each playlist's playback command before returning Android Auto's list. */
    private static void requestEachPlaylist(AndroidAutoBrowseRequest androidAutoRequest, PlaylistsFolderLoad load) {
        List<LibraryPlaylist> playlists = load.libraryPlaylists;
        if (playlists.isEmpty()) {
            deliverAndroidAutoPlaylists(androidAutoRequest, load, "completed");
            return;
        }
        MediaBrowserCompat.MediaItem[] items = new MediaBrowserCompat.MediaItem[playlists.size()];
        load.androidAutoPlaylists = items;
        AtomicInteger remaining = new AtomicInteger(playlists.size());
        for (int index = 0; index < playlists.size(); index++) {
            int playlistIndex = index;
            LibraryPlaylist playlist = playlists.get(index);
            try {
                ListenableFuture<PhoneBrowseResponse> future = load.phoneBrowseClient.patch_requestBrowse(
                        playlist.playlistBrowseId, BACKGROUND_EXECUTOR);
                future.addListener(() -> {
                    try {
                        PhoneBrowseResponse response = future.get();
                        String playbackMediaId = response.patch_getPlaylistPlayButtonMediaId();
                        if (playbackMediaId != null) {
                            synchronized (load) {
                                items[playlistIndex] = createPlaylistItem(playlist, playbackMediaId);
                            }
                        }
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        Logger.printException(() -> "YTM playlist request interrupted", ex);
                    } catch (ExecutionException | RuntimeException ex) {
                        Logger.printException(() -> "YTM playlist request failed", ex);
                    } finally {
                        if (remaining.decrementAndGet() == 0) {
                            deliverAndroidAutoPlaylists(androidAutoRequest, load, "completed");
                        }
                    }
                }, BACKGROUND_EXECUTOR);
            } catch (RuntimeException ex) {
                Logger.printException(() -> "Could not request YTM playlist", ex);
                if (remaining.decrementAndGet() == 0) {
                    deliverAndroidAutoPlaylists(androidAutoRequest, load, "failed");
                }
            }
        }
    }

    private static void deliverAndroidAutoPlaylists(
            AndroidAutoBrowseRequest androidAutoRequest,
            PlaylistsFolderLoad load, String logReason) {
        List<MediaBrowserCompat.MediaItem> items = new ArrayList<>();
        synchronized (load) {
            if (load.deliveryPreparationStarted) return;
            load.deliveryPreparationStarted = true;
            for (MediaBrowserCompat.MediaItem item : load.androidAutoPlaylists) {
                if (item != null) items.add(item);
            }
        }
        try {
            androidAutoRequest.patch_deliverAndroidAutoItems(items);
        } catch (RuntimeException ex) {
            Logger.printException(() -> "Could not deliver Android Auto playlists", ex);
        }
    }

    private static MediaBrowserCompat.MediaItem createPlaylistItem(
            LibraryPlaylist playlist, String playbackMediaId) {
        MediaDescriptionCompat description = new MediaDescriptionCompat(
                playbackMediaId, playlist.title, playlist.subtitle, null, null, playlist.artworkUri,
                null, null);
        return new MediaBrowserCompat.MediaItem(
                description, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE);
    }

    /**
     * Stores playlists collected for one Android Auto Playlists request.
     */
    private static final class PlaylistsFolderLoad {
        // Use the same YTM object throughout loading, even if MusicBrowserService restarts.
        private final PhoneBrowseClient phoneBrowseClient;
        @GuardedBy("this")
        private final List<LibraryPlaylist> libraryPlaylists = new ArrayList<>();
        @GuardedBy("this")
        private final Set<String> seenPlaylistBrowseIds = new HashSet<>();
        @GuardedBy("this")
        private boolean deliveryPreparationStarted;
        private MediaBrowserCompat.MediaItem[] androidAutoPlaylists = new MediaBrowserCompat.MediaItem[0];

        private PlaylistsFolderLoad(PhoneBrowseClient browseClient) {
            this.phoneBrowseClient = browseClient;
        }

    }

    private record LibraryPlaylist(
            String playlistBrowseId, String title, String subtitle, Uri artworkUri) {
    }

}
