/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3341
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches;

import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaDescriptionCompat;

import androidx.annotation.GuardedBy;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.common.util.concurrent.ListenableFuture;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;

/**
 * Supplies Android Auto with playlists from YTM's phone Library.
 *
 * <p>In YTM's two-tab Home/Library layout, Library opens playlists directly and a separate
 * Podcasts tab shows the podcast lists returned for Home. The nested Playlists folder is also supported.
 *
 * <p>{@link #handleAndroidAutoPlaylists} requests the phone Library through YTM, follows pagination,
 * and returns playable playlist cards. Selecting a card lets YTM load the playlist's queue.
 *
 * <p>Successful Library changes trigger {@link #refreshAndroidAutoLibrary}, which reloads the saved
 * Library or Playlists request and Home through YTM's browser service. New Home results update Podcasts.
 */
@SuppressWarnings("unused")
public final class SupportAndroidAutoPatch {
    private static final String PHONE_LIBRARY_BROWSE_ID = "FEmusic_library_landing";
    private static final String EPISODES_FOR_LATER_BROWSE_ID = "VLSE";
    private static final String PLAYLISTS_TITLE_RESOURCE_NAME = "library_playlists_shelf_title";
    // Return collected playlists when this timeout expires.
    private static final int ANDROID_AUTO_PLAYLISTS_TIMEOUT_MILLISECONDS = 30_000;
    private static final int LIBRARY_REFRESH_DELAY_MILLISECONDS = 5_000;
    private static final String ANDROID_AUTO_ROOT_MEDIA_ID = "com.google.android.projection.gearhead";
    private static final String PODCASTS_MEDIA_ID = "morphe:aa:podcasts";
    private static final String PODCASTS_TITLE_RESOURCE_NAME = "offline_podcasts_shelf_title";
    private static final String UPGRADE_PROMPT_MEDIA_ID = "promotion_version_1";
    private static final String FORCE_REFRESH = "com.google.android.apps.youtube.music.mediabrowser.force_refresh";
    private static final Executor BACKGROUND_EXECUTOR = Utils::runOnBackgroundThread;
    private static final Handler refreshHandler = new Handler(Looper.getMainLooper());
    private static final Runnable REFRESH_LIBRARY = SupportAndroidAutoPatch::refreshAndroidAutoLibrary;
    // A user's playlist can also be named "Playlists"; do not use these title matches for playback.
    private static final Set<String> playlistsTitleMatchMediaIds =
            ConcurrentHashMap.newKeySet();
    // Give each Library load a number to distinguish earlier requests from later requests.
    private static final AtomicLong playlistsLoadCounter = new AtomicLong();
    // Remember which Library load last updated each folder on each Android Auto connection.
    @GuardedBy("itself")
    private static final WeakHashMap<Object, Map<String, PlaylistsFolderDelivery>>
            playlistsFolderDeliveries = new WeakHashMap<>();
    // Saved request for playlist updates from Library or the nested Playlists folder.
    @Nullable
    @GuardedBy("SupportAndroidAutoPatch.class")
    private static AndroidAutoSubscription playlistsSubscription;
    @Nullable
    @GuardedBy("SupportAndroidAutoPatch.class")
    private static AndroidAutoSubscription podcastsSubscription;
    @Nullable
    @GuardedBy("SupportAndroidAutoPatch.class")
    private static AndroidAutoSubscription homeSubscription;
    @GuardedBy("SupportAndroidAutoPatch.class")
    private static String androidAutoHomeMediaId;
    @Nullable
    private static volatile String androidAutoLibraryMediaId;
    @GuardedBy("SupportAndroidAutoPatch.class")
    private static List<MediaBrowserCompat.MediaItem> cachedAndroidAutoPodcastFolders =
            Collections.emptyList();

    /** Added to YTM's phone browse client to request the Library and its later pages. */
    public interface PhoneBrowseClient {
        @NonNull ListenableFuture<PhoneBrowseResponse> patch_requestBrowse(
                @NonNull String browseId, @NonNull Executor executor);
        @NonNull ListenableFuture<PhoneBrowseResponse> patch_requestLibraryPagination(
                @NonNull Object paginationCommand, @NonNull Executor executor);
    }

    /**
     * Added to YTM's Library response class. The first page contains TabRenderer wrappers and sections;
     * later pages use {@link #patch_getPaginatedLibraryGrid}. TabRenderer describes the phone page,
     * not Android Auto's tabs.
     */
    public interface PhoneBrowseResponse {
        @NonNull Iterable<PhoneBrowseTab> patch_getTabs();
        @Nullable GridRenderer patch_getPaginatedLibraryGrid();
    }

    /** Added to YTM's TabRenderer wrapper to read its section contents, including Library grids. */
    public interface PhoneBrowseTab {
        @Nullable Iterable<?> patch_getSectionContents();
    }

    /** Added to YTM's Library grid to read its items and pagination commands. */
    public interface GridRenderer {
        // Includes artists and podcasts as well as playlists; filter before returning playlists to Android Auto.
        @NonNull Iterable<?> patch_getItems();
        // YTM's pagination commands: NEXT requests the next Library page; RELOAD refreshes the list.
        @NonNull Iterable<?> patch_getPaginationCommands();
    }

    /** Added to YTM's Android Auto request class to read the requested ID and return its contents. */
    public interface AndroidAutoBrowseRequest {
        @Nullable String patch_getRequestedMediaId();
        // The Android Auto connection for this request, or null if unknown.
        @Nullable Object patch_getBrowserConnection();
        /**
         * Sends the list through YTM, including the hook for
         * {@link SupportAndroidAutoPatch#handleAndroidAutoBrowseResult}.
         * YTM may remove items to keep the returned list within its byte limit.
         */
        void patch_deliverAndroidAutoItems(
                @NonNull List<MediaBrowserCompat.MediaItem> androidAutoItems);
    }

    /** Added to YTM's browser service to repeat saved Android Auto folder requests. */
    public interface AndroidAutoFolderReload {
        void patch_reloadFolder(
                @NonNull String parentMediaId, @NonNull Object connection, @Nullable Bundle options);
    }

    /** Added to YTM's Library item class to read playlist IDs, titles, subtitles, and artwork. */
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
     * Injection point. Save the phone browse client obtained during MusicBrowserService initialization.
     * Clear saved requests and pending refreshes so they cannot use the previous service's connection.
     */
    public static synchronized void setPhoneBrowseClient(@NonNull PhoneBrowseClient client) {
        phoneBrowseClient = client;
        androidAutoLibraryMediaId = null;
        playlistsSubscription = null;
        podcastsSubscription = null;
        homeSubscription = null;
        refreshHandler.removeCallbacks(REFRESH_LIBRARY);
        Logger.printDebug(() -> "Ready to request phone Library contents: " +
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

    // Intercept Android Auto requests for Library or the Playlists folder

    /**
     * Injection point. Load playlists for the Library ID saved by {@link #initializeAndroidAutoTabs}
     * or a Playlists folder identified by {@link #rememberPlaylistsTitleMatch}.
     * YTM has already called detach() on Android Auto's result object, so the list can be sent after this method returns.
     *
     * <p>Library responses are read in the background. Failure or timeout returns the playlists collected so far,
     * or an empty list if none were collected.
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
            if (!requestedMediaId.equals(androidAutoLibraryMediaId) &&
                    !playlistsTitleMatchMediaIds.contains(requestedMediaId)) return false;
            Object connection = androidAutoRequest.patch_getBrowserConnection();
            PlaylistsFolderDelivery folderDelivery;
            synchronized (playlistsFolderDeliveries) {
                if (connection == null) {
                    // Without the connection, there is no way to identify other requests for this same folder.
                    folderDelivery = new PlaylistsFolderDelivery();
                } else {
                    // Reopening Library or Playlists, or refreshing either, can overlap an unfinished load.
                    folderDelivery = playlistsFolderDeliveries
                            .computeIfAbsent(connection, ignored -> new HashMap<>())
                            .computeIfAbsent(requestedMediaId, ignored -> new PlaylistsFolderDelivery());
                }
            }
            PlaylistsFolderLoad load = new PlaylistsFolderLoad(browseClient, folderDelivery);
            try {
                Utils.runOnMainThreadDelayed(
                        () -> deliverAndroidAutoPlaylists(androidAutoRequest, load, "timed out"),
                        ANDROID_AUTO_PLAYLISTS_TIMEOUT_MILLISECONDS);
                requestLibraryPage(androidAutoRequest, load, null);
            } catch (RuntimeException ex) {
                // Only this patch should answer the request, including after failure.
                Logger.printException(() -> "Could not request YTM Library", ex);
                deliverAndroidAutoPlaylists(androidAutoRequest, load, "failed");
            }
            return true;
        } catch (RuntimeException ex) {
            Logger.printException(() -> "Could not handle Android Auto Playlists request", ex);
            return false;
        }
    }

    // Playlist loading and pagination

    /**
     * Reads Library responses on background threads and follows pagination until no command remains.
     * The first page and later pages have different response formats and need separate parsers.
     */
    private static void requestLibraryPage(
            AndroidAutoBrowseRequest androidAutoRequest, PlaylistsFolderLoad load,
            @Nullable Object paginationCommand) {
        boolean firstPage = paginationCommand == null;
        ListenableFuture<PhoneBrowseResponse> libraryResponseFuture = firstPage
                ? load.phoneBrowseClient.patch_requestBrowse(
                        PHONE_LIBRARY_BROWSE_ID, BACKGROUND_EXECUTOR)
                : load.phoneBrowseClient.patch_requestLibraryPagination(
                        paginationCommand, BACKGROUND_EXECUTOR);
        libraryResponseFuture.addListener(() -> {
            synchronized (load) {
                if (load.deliveryPreparationStarted) return;
            }
            try {
                PhoneBrowseResponse libraryResponse = libraryResponseFuture.get();
                Object nextPaginationCommand = firstPage
                        ? appendInitialLibraryPlaylists(libraryResponse, load)
                        : appendPaginatedLibraryPlaylists(libraryResponse, load);
                synchronized (load) {
                    // The timeout may have started returning playlists while this Library response was being read.
                    if (load.deliveryPreparationStarted) return;
                }
                if (nextPaginationCommand != null) {
                    requestLibraryPage(androidAutoRequest, load, nextPaginationCommand);
                    return;
                }
                deliverAndroidAutoPlaylists(androidAutoRequest, load, "completed");
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                Logger.printException(() -> "YTM Library request interrupted", ex);
                deliverAndroidAutoPlaylists(androidAutoRequest, load, "interrupted");
            } catch (ExecutionException | RuntimeException ex) {
                Logger.printException(() -> "YTM Library request failed", ex);
                deliverAndroidAutoPlaylists(androidAutoRequest, load, "failed");
            }
        }, BACKGROUND_EXECUTOR);
    }

    private static Object appendInitialLibraryPlaylists(
            PhoneBrowseResponse libraryResponse, PlaylistsFolderLoad load) {
        Object paginationCommand = null;
        for (PhoneBrowseTab tab : libraryResponse.patch_getTabs()) {
            Iterable<?> sectionContents = tab.patch_getSectionContents();
            if (sectionContents == null) continue;
            for (Object sectionContent : sectionContents) {
                if (!(sectionContent instanceof GridRenderer)) continue;
                GridRenderer gridRenderer = (GridRenderer) sectionContent;
                collectPlaylistsFromGrid(gridRenderer, load);
                if (paginationCommand == null) {
                    paginationCommand = firstPaginationCommand(gridRenderer);
                }
            }
        }
        synchronized (load) {
            Logger.printDebug(() -> "Found playlists in phone Library: " +
                    load.libraryPlaylists.size());
        }
        return paginationCommand;
    }

    private static Object appendPaginatedLibraryPlaylists(
            PhoneBrowseResponse libraryResponse, PlaylistsFolderLoad load) {
        GridRenderer gridRenderer = libraryResponse.patch_getPaginatedLibraryGrid();
        // An unrecognized pagination result ends loading; keep the playlists collected so far.
        if (gridRenderer == null) return null;
        collectPlaylistsFromGrid(gridRenderer, load);
        synchronized (load) {
            Logger.printDebug(() -> "Found playlists in phone Library: " +
                    load.libraryPlaylists.size());
        }
        return firstPaginationCommand(gridRenderer);
    }

    /** Skips unreadable Library items so one failure does not discard the remaining playlists. */
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

    @Nullable
    private static Object firstPaginationCommand(GridRenderer gridRenderer) {
        // TODO: Check whether pagination needs every command when YTM returns more than one.
        Iterator<?> commands = gridRenderer.patch_getPaginationCommands().iterator();
        return commands.hasNext() ? commands.next() : null;
    }

    // Playlist titles and artwork

    /** Injection point. Exclude artists and shows; skip commands that identify different playlists. */
    @Nullable
    public static String resolvePlaylistBrowseId(
            @Nullable String firstBrowseId, @Nullable String secondBrowseId) {
        if (firstBrowseId != null && !firstBrowseId.startsWith("VL")) firstBrowseId = null;
        if (secondBrowseId != null && !secondBrowseId.startsWith("VL")) secondBrowseId = null;
        if (firstBrowseId == null) return secondBrowseId;
        if (secondBrowseId == null || firstBrowseId.equals(secondBrowseId)) return firstBrowseId;
        return null;
    }

    /**
     * Builds playable cards from Library items. YTM's playback command accepts a playlist ID without
     * a song ID, so building a card does not require fetching the playlist's songs.
     */
    private static void addLibraryPlaylist(
            PhoneBrowseItem libraryItem, PlaylistsFolderLoad load) {
        String playlistBrowseId = libraryItem.patch_getPlaylistBrowseId();
        if (playlistBrowseId == null) return;
        // TODO: Verify playlist-ID playback for Episodes for Later (VLSE).
        if (EPISODES_FOR_LATER_BROWSE_ID.equals(playlistBrowseId)) return;

        CharSequence titleText = libraryItem.patch_getTitle();
        String title = titleText == null ? "" : titleText.toString();
        if (title.isEmpty()) return;
        // Phone page IDs prefix playback playlist IDs with "VL".
        String mediaId = createPlaylistMediaId(playlistBrowseId.substring(2));
        // Keep the playlist available if its subtitle or artwork cannot be read.
        MediaDescriptionCompat description = new MediaDescriptionCompat(
                mediaId, title, subtitleOrEmpty(libraryItem), null, null,
                artworkUriOrNull(libraryItem), null, null);
        MediaBrowserCompat.MediaItem playlist = new MediaBrowserCompat.MediaItem(
                description, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE);
        // Metadata reads stay outside the lock so they cannot delay timeout handling.
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

    /**
     * Stops collection before sending the result so a concurrent timeout cannot send it twice.
     */
    private static void deliverAndroidAutoPlaylists(
            AndroidAutoBrowseRequest androidAutoRequest,
            PlaylistsFolderLoad load, String logReason) {
        List<MediaBrowserCompat.MediaItem> collectedPlaylists = load.takePlaylistsForDelivery();
        if (collectedPlaylists == null) return;
        load.folderDelivery.deliver(load.loadNumber, androidAutoRequest, collectedPlaylists, logReason);
    }

    /** Replaced during patching with calls to YTM's playlist command builder and Android Auto media ID encoder. */
    private static String createPlaylistMediaId(String playlistId) {
        return null;
    }

    /** Stores playable items collected for one Android Auto Library or Playlists request. */
    private static final class PlaylistsFolderLoad {
        // Use the same YTM object throughout pagination, even if MusicBrowserService restarts.
        private final PhoneBrowseClient phoneBrowseClient;
        private final long loadNumber = playlistsLoadCounter.incrementAndGet();
        private final PlaylistsFolderDelivery folderDelivery;
        @GuardedBy("this")
        private final List<MediaBrowserCompat.MediaItem> libraryPlaylists = new ArrayList<>();
        @GuardedBy("this")
        private final Set<String> seenPlaylistBrowseIds = new HashSet<>();
        @GuardedBy("this")
        private boolean deliveryPreparationStarted;

        private PlaylistsFolderLoad(PhoneBrowseClient browseClient, PlaylistsFolderDelivery folderDelivery) {
            this.phoneBrowseClient = browseClient;
            this.folderDelivery = folderDelivery;
        }

        /**
         * Allows only one delivery attempt, whether pagination finishes, fails, or times out.
         *
         * @return a copy of the collected playlists, possibly empty; null if delivery preparation already started
         */
        @Nullable
        private synchronized List<MediaBrowserCompat.MediaItem> takePlaylistsForDelivery() {
            if (deliveryPreparationStarted) return null;
            deliveryPreparationStarted = true;
            return new ArrayList<>(libraryPlaylists);
        }
    }

    /** Prevents older results from replacing newer results for the same folder and Android Auto connection. */
    private static final class PlaylistsFolderDelivery {
        @GuardedBy("this")
        private long lastDeliveredLoadNumber;

        // Keep the check, send, and update together so another load cannot send between them.
        private synchronized void deliver(
                long loadNumber, AndroidAutoBrowseRequest androidAutoRequest,
                List<MediaBrowserCompat.MediaItem> androidAutoPlaylistItems, String logReason) {
            // A newer load still in progress does not prevent this one from returning playlists.
            if (loadNumber < lastDeliveredLoadNumber) return;
            Logger.printDebug(() -> "YTM Library " + logReason + "; returning " +
                    androidAutoPlaylistItems.size() + " playlists");
            try {
                androidAutoRequest.patch_deliverAndroidAutoItems(androidAutoPlaylistItems);
                // A failed send must not prevent an older load from returning its playlists.
                lastDeliveredLoadNumber = loadNumber;
            } catch (RuntimeException ex) {
                // Do not retry: YTM may already consider this request answered.
                // Reopening Library or Playlists starts a new request.
                Logger.printException(() -> "Could not deliver Android Auto playlists", ex);
            }
        }
    }

    // Refresh after Library changes

    /**
     * Injection point. Save the Library or Playlists request, plus Home and Podcasts, for later refreshes.
     */
    public static synchronized void rememberAndroidAutoSubscription(
            @NonNull AndroidAutoFolderReload browserService,
            @Nullable String parentMediaId,
            @NonNull Object connection) {
        if (parentMediaId == null) return;
        if (parentMediaId.equals(androidAutoLibraryMediaId) ||
                playlistsTitleMatchMediaIds.contains(parentMediaId)) {
            playlistsSubscription = new AndroidAutoSubscription(browserService, parentMediaId, connection);
        } else if (PODCASTS_MEDIA_ID.equals(parentMediaId)) {
            podcastsSubscription = new AndroidAutoSubscription(browserService, parentMediaId, connection);
        } else if (parentMediaId.equals(androidAutoHomeMediaId)) {
            homeSubscription = new AndroidAutoSubscription(browserService, parentMediaId, connection);
        }
    }

    /** Injection point. Refresh Android Auto after a successful request changes the Library. */
    public static void onRequestSucceeded(@Nullable String endpoint) {
        if (endpoint == null) return;
        try {
            switch (endpoint) {
                case "browse/edit_playlist":
                case "like/like":
                case "like/removelike":
                case "playlist/create":
                case "playlist/delete":
                    scheduleLibraryRefresh();
            }
        } catch (RuntimeException ex) {
            Logger.printException(() -> "Could not schedule Android Auto Library refresh", ex);
        }
    }

    /** Combines completed Library changes into one delayed refresh. */
    private static synchronized void scheduleLibraryRefresh() {
        if (playlistsSubscription == null && homeSubscription == null) return;
        // Wait for updated playlist artwork before refreshing.
        refreshHandler.removeCallbacks(REFRESH_LIBRARY);
        refreshHandler.postDelayed(REFRESH_LIBRARY, LIBRARY_REFRESH_DELAY_MILLISECONDS);
    }

    /**
     * Repeats the saved Library or Playlists request to fetch the phone Library again.
     * Reloads Home to obtain updated podcast lists.
     */
    private static void refreshAndroidAutoLibrary() {
        AndroidAutoSubscription playlists;
        AndroidAutoSubscription home;
        // Refresh the current connection if Android Auto reconnected during the delay.
        synchronized (SupportAndroidAutoPatch.class) {
            playlists = playlistsSubscription;
            home = homeSubscription;
        }
        // Reloading needs YTM's lock; YTM's result delivery needs this class's lock.
        // Reload outside synchronized to avoid the two threads waiting for each other.
        if (playlists != null) playlists.reload(false);
        // Force a new Home request to include show changes; its response refreshes Podcasts.
        if (home != null) home.reload(true);
    }

    /**
     * Saves an Android Auto list's ID, service, and connection so its contents can be requested again.
     */
    private static final class AndroidAutoSubscription {
        private final WeakReference<AndroidAutoFolderReload> browserService;
        private final String parentMediaId;
        private final WeakReference<Object> connection;

        private AndroidAutoSubscription(
                AndroidAutoFolderReload browserService, String parentMediaId, Object connection) {
            this.browserService = new WeakReference<>(browserService);
            this.parentMediaId = parentMediaId;
            this.connection = new WeakReference<>(connection);
        }

        private void reload(boolean forceRefresh) {
            AndroidAutoFolderReload service = browserService.get();
            Object connectedBrowser = connection.get();
            // Weak references allow the browser service and connection to be released after disconnection.
            if (service == null || connectedBrowser == null) return;
            try {
                Bundle options = null;
                if (forceRefresh) {
                    options = new Bundle();
                    options.putBoolean(FORCE_REFRESH, true);
                }
                service.patch_reloadFolder(parentMediaId, connectedBrowser, options);
            } catch (RuntimeException ex) {
                Logger.printException(() -> "Could not refresh Android Auto folder: " + parentMediaId, ex);
            }
        }
    }

    // Podcasts

    /**
     * Injection point for YTM's media-ID getter. Returns true for the added Podcasts tab,
     * whose literal string ID cannot be decoded as a Base64-encoded protobuf.
     */
    public static boolean isAndroidAutoPodcastsMediaId(@Nullable String mediaId) {
        return PODCASTS_MEDIA_ID.equals(mediaId);
    }

    /**
     * Injection point. Modify lists before YTM sends them to Android Auto.
     * Add a Podcasts tab, cache Home's podcast folders, and return them when that tab is requested.
     * New Home results also refresh Podcasts if it has been opened.
     */
    @Nullable
    public static synchronized List<MediaBrowserCompat.MediaItem> handleAndroidAutoBrowseResult(
            @NonNull AndroidAutoBrowseRequest request,
            @Nullable List<MediaBrowserCompat.MediaItem> ytmItems) {
        try {
            String parentMediaId = request.patch_getRequestedMediaId();
            if (ANDROID_AUTO_ROOT_MEDIA_ID.equals(parentMediaId)) {
                return initializeAndroidAutoTabs(ytmItems);
            }
            if (PODCASTS_MEDIA_ID.equals(parentMediaId)) {
                return new ArrayList<>(cachedAndroidAutoPodcastFolders);
            }
            if (parentMediaId != null && parentMediaId.equals(androidAutoHomeMediaId) &&
                    ytmItems != null) {
                cacheAndroidAutoPodcastFolders(ytmItems);
                refreshPodcastsAfterHomeLoad();
            }
        } catch (RuntimeException ex) {
            Logger.printException(() -> "Could not handle Android Auto browse result", ex);
        }
        return ytmItems;
    }

    @Nullable
    @GuardedBy("SupportAndroidAutoPatch.class")
    private static List<MediaBrowserCompat.MediaItem> initializeAndroidAutoTabs(
            @Nullable List<MediaBrowserCompat.MediaItem> rootTabs) {
        // Do not reuse saved requests or podcast content after the main tabs reload.
        playlistsSubscription = null;
        podcastsSubscription = null;
        homeSubscription = null;
        refreshHandler.removeCallbacks(REFRESH_LIBRARY);
        androidAutoHomeMediaId = null;
        androidAutoLibraryMediaId = null;
        cachedAndroidAutoPodcastFolders = Collections.emptyList();
        // Add Podcasts only when YTM supplies Home and Library without a Podcasts tab.
        if (rootTabs == null || rootTabs.size() != 2) return rootTabs;

        androidAutoHomeMediaId = rootTabs.get(0).a();
        // Podcasts gets its own tab, so Library can open playlists directly.
        androidAutoLibraryMediaId = rootTabs.get(1).a();
        List<MediaBrowserCompat.MediaItem> updatedRootTabs = new ArrayList<>(rootTabs);
        MediaDescriptionCompat podcastsDescription = new MediaDescriptionCompat(
                PODCASTS_MEDIA_ID,
                ResourceUtils.getString(PODCASTS_TITLE_RESOURCE_NAME),
                null, null, null, null, null, null);
        updatedRootTabs.add(1, new MediaBrowserCompat.MediaItem(
                podcastsDescription, MediaBrowserCompat.MediaItem.FLAG_BROWSABLE));
        return updatedRootTabs;
    }

    @GuardedBy("SupportAndroidAutoPatch.class")
    private static void refreshPodcastsAfterHomeLoad() {
        AndroidAutoSubscription subscription = podcastsSubscription;
        if (subscription == null) return;
        // New Home results can change the podcast folder IDs.
        // Always queue the reload so handleAndroidAutoBrowseResult releases its lock first.
        refreshHandler.post(() -> {
            synchronized (SupportAndroidAutoPatch.class) {
                // Reloading the main tabs or reconnecting replaces the saved Podcasts request.
                if (podcastsSubscription != subscription) return;
            }
            subscription.reload(false);
        });
    }

    @GuardedBy("SupportAndroidAutoPatch.class")
    private static void cacheAndroidAutoPodcastFolders(List<MediaBrowserCompat.MediaItem> homeItems) {
        List<MediaBrowserCompat.MediaItem> folders = new ArrayList<>();
        // TODO: The server omits songs and playlists from Android Auto Speed dial; obtain them from phone Home.
        // Some podcast lists have no layout hint; include them if they are browsable.
        for (MediaBrowserCompat.MediaItem item : homeItems) {
            // YTM's upgrade prompt is also browsable; exclude it explicitly.
            if (!item.b() || UPGRADE_PROMPT_MEDIA_ID.equals(item.a())) continue;
            folders.add(item);
        }
        cachedAndroidAutoPodcastFolders = folders;
    }
}
