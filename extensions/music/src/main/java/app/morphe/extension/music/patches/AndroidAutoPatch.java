/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3341
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches;

import static app.morphe.extension.music.patches.AndroidAutoBrowseResponseParser.BROWSE;
import static app.morphe.extension.music.patches.AndroidAutoBrowseResponseParser.BROWSE_ID;
import static app.morphe.extension.music.patches.AndroidAutoBrowseResponseParser.BrowseItem;
import static app.morphe.extension.music.patches.AndroidAutoBrowseResponseParser.HomePage;
import static app.morphe.extension.music.patches.AndroidAutoBrowseResponseParser.PLAYLIST_ID;
import static app.morphe.extension.music.patches.AndroidAutoBrowseResponseParser.WATCH_PLAYLIST;
import static app.morphe.extension.music.patches.AndroidAutoBrowseResponseParser.homePage;
import static app.morphe.extension.music.patches.AndroidAutoBrowseResponseParser.isPlayable;
import static app.morphe.extension.music.patches.AndroidAutoBrowseResponseParser.isUploadedCollection;
import static app.morphe.extension.music.patches.AndroidAutoBrowseResponseParser.libraryItems;
import static app.morphe.extension.music.patches.ProtoMessage.WIRE_LENGTH_DELIMITED;

import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcel;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaDescriptionCompat;

import androidx.annotation.GuardedBy;
import androidx.annotation.Nullable;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.ByteArrayOutputStream;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import app.morphe.extension.music.patches.AndroidAutoSearchResponseParser.ResultCategory;
import app.morphe.extension.music.patches.AndroidAutoSearchResponseParser.SearchResult;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;

/**
 * Uses YTM's phone browse responses for music recommendations and Library content.
 * Reuses podcast folders from YTM's Android Auto Home response in Home and the Podcasts tab.
 * Phone browse IDs identify server pages; Auto media IDs identify folders or carry playback commands.
 */
@SuppressWarnings("unused")
public final class AndroidAutoPatch {

    public interface PhoneBrowseClient {
        ListenableFuture<PhoneBrowseResponse> patch_requestBrowse(
                String browseId, Executor executor);
        ListenableFuture<PhoneBrowseResponse> patch_requestBrowseContinuation(
                String continuation, Executor executor);
        ListenableFuture<PhoneBrowseResponse> patch_requestLibraryPagination(
                Object paginationCommand, Executor executor);
    }

    /**
     * Library's first page contains phone TabRenderer wrappers and their section lists;
     * later pages use {@link #patch_getPaginatedLibraryGrid}.
     */
    public interface PhoneBrowseResponse {
        byte[] patch_responseBytes();
        Iterable<PhoneBrowseTab> patch_getTabs();
        @Nullable GridRenderer patch_getPaginatedLibraryGrid();
    }

    public interface PhoneBrowseTab {
        @Nullable Iterable<?> patch_getSectionContents();
    }

    /** Bridge to YTM's phone Library grid, exposing its items and pagination commands. */
    public interface GridRenderer {
        /**
         * Includes artists and podcasts as well as playlists; filter before returning playlists to Android Auto.
         */
        Iterable<?> patch_getItems();
        /**
         * YTM's pagination commands: NEXT requests the next Library page; RELOAD refreshes the list.
         */
        Iterable<?> patch_getPaginationCommands();
    }

    /** Android Auto's request for the children of a folder media ID. */
    public interface BrowseRequest {
        @Nullable String patch_getRequestedMediaId();

        /**
         * The Android Auto connection for this request, or null if unknown.
         */
        @Nullable Object patch_getBrowserConnection();

        /**
         * Sends items through YTM and {@link AndroidAutoPatch#handleBrowseResult}.
         * YTM may remove items to keep the returned list within its byte limit.
         */
        void patch_deliverBrowseItems(List<MediaBrowserCompat.MediaItem> items);
    }

    /** Calls YTM's browser service to reload an Auto page, such as Albums. */
    public interface FolderReload {
        void patch_reloadFolder(String parentMediaId, Object connection, @Nullable Bundle options);
    }

    public interface PhoneBrowseItem {
        /**
         * Returns a playlist page ID, or null for missing or conflicting IDs.
         * YTM throws for non-page commands; {@link AndroidAutoPatch#collectPlaylistsFromGrid} skips those items.
         */
        @Nullable String patch_getPlaylistBrowseId();
        @Nullable Uri patch_getArtworkUri();
        @Nullable CharSequence patch_getTitle();
        @Nullable CharSequence patch_getSubtitle();
    }

    private static final class PlaylistsFolderLoad {
        // Use the same YTM object throughout pagination, even if MusicBrowserService restarts.
        private final PhoneBrowseClient phoneBrowseClient;
        private final long loadNumber = browseLoadCounter.incrementAndGet();
        private final FolderDelivery folderDelivery;
        @GuardedBy("this")
        private final List<MediaBrowserCompat.MediaItem> libraryPlaylists = new ArrayList<>();
        @GuardedBy("this")
        private final Set<String> seenPlaylistBrowseIds = new HashSet<>();
        @GuardedBy("this")
        private boolean deliveryPreparationStarted;

        private PlaylistsFolderLoad(PhoneBrowseClient browseClient, FolderDelivery folderDelivery) {
            this.phoneBrowseClient = browseClient;
            this.folderDelivery = folderDelivery;
        }

        private synchronized boolean isDeliveryPreparationStarted() {
            return deliveryPreparationStarted;
        }

        private synchronized void addPlaylistIfNew(String browseId, MediaBrowserCompat.MediaItem playlist) {
            if (deliveryPreparationStarted || !seenPlaylistBrowseIds.add(browseId)) {
                return;
            }
            libraryPlaylists.add(playlist);
        }

        private synchronized int getPlaylistCount() {
            return libraryPlaylists.size();
        }

        /**
         * Allows only one delivery attempt, whether pagination finishes, fails, or times out.
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
    private static final class FolderDelivery {
        @GuardedBy("this")
        private long lastDeliveredLoadNumber;

        // Keep the check, send, and update together so another load cannot send between them.
        private synchronized void deliver(long loadNumber, Runnable send) {
            // Skip this load only if a newer load has already delivered results.
            if (loadNumber < lastDeliveredLoadNumber) return;
            try {
                send.run();
                // A failed send must not prevent an older load from returning its results.
                lastDeliveredLoadNumber = loadNumber;
            } catch (RuntimeException ex) {
                // Do not retry: YTM may already consider this request answered.
                // Reopening the page starts a new request.
                Logger.printException(() -> "Could not deliver Android Auto results", ex);
            }
        }
    }

    private static FolderDelivery getFolderDelivery(BrowseRequest request) {
        Object connection = request.patch_getBrowserConnection();
        // Without the connection, we cannot identify other loads updating the same Auto page.
        if (connection == null) return new FolderDelivery();
        synchronized (folderDeliveries) {
            return folderDeliveries.computeIfAbsent(connection, ignored -> new HashMap<>())
                    .computeIfAbsent(request.patch_getRequestedMediaId(), ignored -> new FolderDelivery());
        }
    }

    /** Remembers which Auto page (such as Albums) to reload on a connection after Library changes. */
    private record FolderSubscription(WeakReference<FolderReload> browserService,
                                           String parentMediaId, WeakReference<Object> connection) {
        private FolderSubscription(FolderReload browserService,
                                        String parentMediaId, Object connection) {
            this(new WeakReference<>(browserService), parentMediaId, new WeakReference<>(connection));
        }

        private void reload(boolean forceRefresh) {
            FolderReload service = browserService.get();
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

    private static final String PHONE_LIBRARY_BROWSE_ID = "FEmusic_library_landing";
    private static final String EPISODES_FOR_LATER_BROWSE_ID = "VLSE";
    private static final String SPEED_DIAL_BROWSE_ID = "FEmusic_listen_again";

    private static final String ROOT_MEDIA_ID = "com.google.android.projection.gearhead";
    private static final String UPGRADE_PROMPT_MEDIA_ID = "promotion_version_1";
    private static final String PODCASTS_MEDIA_ID = "morphe:aa:podcasts";
    private static final String SPEED_DIAL_MEDIA_ID = "morphe:aa:speed-dial";

    private static final String HOME_SHELF_PREFIX = "morphe-aa-home://section/";
    private static final String PODCAST_COLLECTION_PREFIX = "morphe-aa-podcast://collection/";
    private static final String LIBRARY_PREFIX = "morphe-aa-library://collection/";
    private static final String FORCE_REFRESH = "com.google.android.apps.youtube.music.mediabrowser.force_refresh";

    public static final String PLAYLISTS_TITLE_RESOURCE_STRING
            = ResourceUtils.getString("library_playlists_shelf_title");
    private static final String PODCASTS_TITLE_RESOURCE_NAME = "offline_podcasts_shelf_title";

    // Return collected playlists when this timeout expires.
    private static final int PLAYLIST_TIMEOUT_MS = 30_000;
    private static final int LIBRARY_REFRESH_DELAY_MS = 5_000;
    static final long DEADLINE_MS = 8_000;

    private static final int MAX_COMMAND_BYTES = 16 * 1024;
    static final int MAX_MEDIA_ID_LENGTH = 24 * 1024;
    // Android Auto content style hints.
    private static final String CONTENT_STYLE_SINGLE_ITEM_HINT = "android.media.browse.CONTENT_STYLE_SINGLE_ITEM_HINT";
    private static final String CONTENT_STYLE_BROWSABLE_HINT = "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT";
    private static final String CONTENT_STYLE_PLAYABLE_HINT = "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT";
    private static final String CONTENT_STYLE_GROUP_TITLE_HINT = "android.media.browse.CONTENT_STYLE_GROUP_TITLE_HINT";
    private static final int CONTENT_STYLE_LIST = 1;
    private static final int CONTENT_STYLE_GRID = 2;
    private static final int CONTENT_STYLE_CATEGORY_LIST = 3;

    private enum LibraryFolder {
        LAST_PLAYED("FEmusic_history", "morphe_music_android_auto_last_played", "history"),
        PLAYLISTS(PHONE_LIBRARY_BROWSE_ID, "library_playlists_shelf_title", "playlist"),
        ALBUMS("FEmusic_liked_albums", "library_albums_shelf_title", "album"),
        SONGS("FEmusic_liked_videos", "library_songs_shelf_title", "audio"),
        ARTISTS("FEmusic_library_corpus_track_artists", "library_artists_shelf_title", "person_audio"),
        SUBSCRIPTIONS("FEmusic_library_corpus_artists", "morphe_music_android_auto_subscriptions", "subscriptions"),
        PODCASTS("FEmusic_library_non_music_audio_list", "offline_podcasts_shelf_title", "podcast"),
        UPLOADED_ALBUMS("FEmusic_library_privately_owned_releases", "library_uploads_title", "upload"),
        UPLOADED_SONGS("FEmusic_library_privately_owned_tracks", "library_uploads_title", "upload"),
        UPLOADED_ARTISTS("FEmusic_library_privately_owned_artists", "library_uploads_title", "upload");

        final String browseId;
        final String titleResource;
        final String iconName;

        LibraryFolder(String browseId, String titleResource, String iconName) {
            this.browseId = browseId;
            this.titleResource = titleResource;
            this.iconName = iconName;
        }

        String mediaId() { return LIBRARY_PREFIX + name(); }

        String artworkUri() {
            // Use this install's package name so Library icons still load when GmsCore support renames the app.
            return "android.resource://" + Utils.getContext().getPackageName()
                    + "/drawable/yt_outline_experimental_" + iconName + "_vd_theme_24";
        }

        MediaBrowserCompat.MediaItem mediaItem() {
            Bundle extras = new Bundle();
            boolean uploads = this == UPLOADED_ALBUMS || this == UPLOADED_SONGS || this == UPLOADED_ARTISTS;
            // Show the category itself as a row; the child hints apply after opening it.
            extras.putInt(CONTENT_STYLE_SINGLE_ITEM_HINT, CONTENT_STYLE_LIST);
            extras.putInt(CONTENT_STYLE_BROWSABLE_HINT,
                    this == LAST_PLAYED || uploads ? CONTENT_STYLE_LIST : CONTENT_STYLE_CATEGORY_LIST);
            extras.putInt(CONTENT_STYLE_PLAYABLE_HINT,
                    this == LAST_PLAYED || this == PODCASTS ? CONTENT_STYLE_LIST : CONTENT_STYLE_GRID);
            if (this == LAST_PLAYED) extras.putBoolean("com.google.android.apps.youtube.music.mediabrowser.IS_LAST_PLAYED", true);
            return new MediaBrowserCompat.MediaItem(new MediaDescriptionCompat(
                    mediaId(), ResourceUtils.getString(titleResource), null, null, null,
                    Uri.parse(artworkUri()), extras, null), MediaBrowserCompat.MediaItem.FLAG_BROWSABLE);
        }
    }

    static final Executor BACKGROUND_EXECUTOR = Utils::runOnBackgroundThread;
    static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final Runnable REFRESH_LIBRARY = AndroidAutoPatch::refreshLibrary;

    // A user's playlist can also be named "Playlists"; do not use these title matches for playback.
    private static final Set<String> playlistsTitleMatchMediaIds = ConcurrentHashMap.newKeySet();
    // Give each browse load a number to distinguish earlier requests from later requests.
    private static final AtomicLong browseLoadCounter = new AtomicLong();
    // Remember which load last updated each folder on each Android Auto connection.
    @GuardedBy("itself")
    private static final WeakHashMap<Object, Map<String, FolderDelivery>> folderDeliveries = new WeakHashMap<>();
    // Most recently opened Library folder, podcast show, or Home shelf to refresh after Library changes.
    @Nullable
    @GuardedBy("AndroidAutoPatch.class")
    private static FolderSubscription librarySubscription;
    @Nullable
    @GuardedBy("AndroidAutoPatch.class")
    private static FolderSubscription podcastsSubscription;
    @Nullable
    @GuardedBy("AndroidAutoPatch.class")
    private static FolderSubscription homeSubscription;
    @Nullable
    @GuardedBy("AndroidAutoPatch.class")
    private static String homeMediaId;
    @Nullable
    private static volatile String libraryMediaId;
    @GuardedBy("AndroidAutoPatch.class")
    private static List<MediaBrowserCompat.MediaItem> cachedPodcastFolders = Collections.emptyList();

    @Nullable
    private static volatile PhoneBrowseClient phoneBrowseClient;

    private AndroidAutoPatch() {
    }

    // Phone browse client and Playlists folder identification

    /**
     * Injection point. Save the phone browse client obtained during MusicBrowserService initialization.
     * Clear saved requests and pending refreshes so they cannot use the previous service's connection.
     */
    public static synchronized void setPhoneBrowseClient(PhoneBrowseClient client) {
        phoneBrowseClient = client;
        libraryMediaId = null;
        librarySubscription = null;
        podcastsSubscription = null;
        homeSubscription = null;
        mainHandler.removeCallbacks(REFRESH_LIBRARY);
        Logger.printDebug(() -> "Ready to request phone Library contents: " +
                client.getClass().getName());
    }

    /** Injection point. Identify the Playlists folder by its translated title; its ID varies. */
    public static void rememberPlaylistsTitleMatch(@Nullable String mediaId,
                                                   @Nullable CharSequence title) {
        if (title == null || (PLAYLISTS_TITLE_RESOURCE_STRING != null
                && !PLAYLISTS_TITLE_RESOURCE_STRING.contentEquals(title))) return;
        if (mediaId != null) playlistsTitleMatchMediaIds.add(mediaId);
    }

    // Route expanded Home groups and Library requests

    /**
     * Injection point after YTM detaches the result for asynchronous delivery.
     * @return true if the patch will answer the request; false to leave it to YTM.
     */
    public static boolean handleBrowseRequest(BrowseRequest request) {
        try {
            PhoneBrowseClient browseClient = phoneBrowseClient;
            if (browseClient == null) return false;
            String requestedMediaId = request.patch_getRequestedMediaId();
            if (requestedMediaId == null) return false;
            if (requestedMediaId.equals(SPEED_DIAL_MEDIA_ID)) {
                HomeLoad.forSpeedDial(request).start();
                return true;
            }
            if (requestedMediaId.startsWith(HOME_SHELF_PREFIX)) {
                HomeLoad.forShelf(request, requestedMediaId).start();
                return true;
            }
            if (requestedMediaId.equals(libraryMediaId)) {
                PreparedItems folders = new PreparedItems();
                for (LibraryFolder folder : LibraryFolder.values()) {
                    // Native Auto places uploads inside the corresponding Albums, Songs, and Artists categories.
                    if (folder == LibraryFolder.UPLOADED_ALBUMS || folder == LibraryFolder.UPLOADED_SONGS
                            || folder == LibraryFolder.UPLOADED_ARTISTS) continue;
                    folders.add(folder.mediaItem());
                }
                request.patch_deliverBrowseItems(folders);
                return true;
            }
            if (requestedMediaId.startsWith(PODCAST_COLLECTION_PREFIX)
                    || (requestedMediaId.startsWith(LIBRARY_PREFIX)
                            && !requestedMediaId.equals(LibraryFolder.PLAYLISTS.mediaId()))) {
                new LibraryLoad(request, requestedMediaId).start();
                return true;
            }
            if (!requestedMediaId.equals(LibraryFolder.PLAYLISTS.mediaId())
                    && !playlistsTitleMatchMediaIds.contains(requestedMediaId)) return false;

            PlaylistsFolderLoad load = new PlaylistsFolderLoad(browseClient, getFolderDelivery(request));
            try {
                Utils.runOnMainThreadDelayed(
                        () -> deliverPlaylists(request, load, "timed out"),
                        PLAYLIST_TIMEOUT_MS);
                requestLibraryPage(request, load, null);
            } catch (RuntimeException ex) {
                // Only this patch should answer the request, including after failure.
                Logger.printException(() -> "Could not request YTM Library", ex);
                deliverPlaylists(request, load, "failed");
            }
            return true;
        } catch (RuntimeException ex) {
            Logger.printException(() -> "Could not handle Android Auto browse request", ex);
            return false;
        }
    }

    // Library > Playlists: native grid loading and pagination

    /**
     * Reads Library responses on background threads and follows pagination until no command remains.
     * The first page and later pages have different response formats and need separate parsers.
     */
    private static void requestLibraryPage(BrowseRequest request,
                                           PlaylistsFolderLoad load,
                                           @Nullable Object paginationCommand) {
        boolean firstPage = paginationCommand == null;
        ListenableFuture<PhoneBrowseResponse> libraryResponseFuture = firstPage
                ? load.phoneBrowseClient.patch_requestBrowse(
                PHONE_LIBRARY_BROWSE_ID, BACKGROUND_EXECUTOR)
                : load.phoneBrowseClient.patch_requestLibraryPagination(
                paginationCommand, BACKGROUND_EXECUTOR);
        libraryResponseFuture.addListener(() -> {
            if (load.isDeliveryPreparationStarted()) return;

            try {
                PhoneBrowseResponse libraryResponse = libraryResponseFuture.get();
                Object nextPaginationCommand = firstPage
                        ? appendInitialLibraryPlaylists(libraryResponse, load)
                        : appendPaginatedLibraryPlaylists(libraryResponse, load);

                // The timeout may have started returning playlists while this Library response was being read.
                if (load.isDeliveryPreparationStarted()) return;

                if (nextPaginationCommand != null) {
                    requestLibraryPage(request, load, nextPaginationCommand);
                    return;
                }
                deliverPlaylists(request, load, "completed");
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                Logger.printException(() -> "YTM Library request interrupted", ex);
                deliverPlaylists(request, load, "interrupted");
            } catch (ExecutionException | RuntimeException ex) {
                Logger.printException(() -> "YTM Library request failed", ex);
                deliverPlaylists(request, load, "failed");
            }
        }, BACKGROUND_EXECUTOR);
    }

    @Nullable
    private static Object appendInitialLibraryPlaylists(PhoneBrowseResponse libraryResponse,
                                                        PlaylistsFolderLoad load) {
        Object paginationCommand = null;
        for (PhoneBrowseTab tab : libraryResponse.patch_getTabs()) {
            Iterable<?> sectionRenderers = tab.patch_getSectionContents();
            if (sectionRenderers == null) continue;
            for (Object sectionRenderer : sectionRenderers) {
                if (!(sectionRenderer instanceof GridRenderer gridRenderer)) continue;
                collectPlaylistsFromGrid(gridRenderer, load);
                if (paginationCommand == null) {
                    paginationCommand = firstPaginationCommand(gridRenderer);
                }
            }
        }

        Logger.printDebug(() -> "Found playlists in phone Library: " + load.getPlaylistCount());
        return paginationCommand;
    }

    @Nullable
    private static Object appendPaginatedLibraryPlaylists(PhoneBrowseResponse libraryResponse,
                                                          PlaylistsFolderLoad load) {
        GridRenderer gridRenderer = libraryResponse.patch_getPaginatedLibraryGrid();
        // An unrecognized pagination result ends loading; keep the playlists collected so far.
        if (gridRenderer == null) return null;
        collectPlaylistsFromGrid(gridRenderer, load);
        Logger.printDebug(() -> "Found playlists in phone Library: " + load.getPlaylistCount());
        return firstPaginationCommand(gridRenderer);
    }

    /** Skips unreadable Library items so one failure does not discard the remaining playlists. */
    private static void collectPlaylistsFromGrid(GridRenderer gridRenderer,
                                                 PlaylistsFolderLoad load) {
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
    public static String resolvePlaylistBrowseId(@Nullable String firstBrowseId,
                                                 @Nullable String secondBrowseId) {
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
    private static void addLibraryPlaylist(PhoneBrowseItem libraryItem,
                                           PlaylistsFolderLoad load) {
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
        load.addPlaylistIfNew(playlistBrowseId, playlist);
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

    @Nullable
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
     * Returns the playlists collected so far, including after failure or timeout.
     * Stops collection before sending so a concurrent completion cannot deliver twice.
     */
    private static void deliverPlaylists(BrowseRequest request,
            PlaylistsFolderLoad load, String logReason) {
        List<MediaBrowserCompat.MediaItem> collectedPlaylists = load.takePlaylistsForDelivery();
        if (collectedPlaylists == null) return;
        load.folderDelivery.deliver(load.loadNumber, () -> {
            Logger.printDebug(() -> "YTM Library: " + logReason + "; returning: " +
                    collectedPlaylists.size() + " playlists");
            request.patch_deliverBrowseItems(collectedPlaylists);
        });
    }

    /** Replaced during patching with calls to YTM's playlist command builder and Android Auto media ID encoder. */
    @Nullable
    static String createPlaylistMediaId(String playlistId) {
        return null;
    }

    private static final int PLAYLIST_SUPPORTED_CONFIG = 96_593_618;
    private static final int PLAYLIST_MUSIC_CONFIG = 96_593_634;
    private static final int PLAYLIST_OPTIONS = 7;
    private static final int PLAYLIST_COLLECTION_OPTIONS = 2;
    // Both option messages encode their value as a field-2 varint.
    private static final byte OPTION_VALUE_TAG = 0x10;
    private static final byte ORDERED_PLAYBACK = 0x02;
    private static final int UPLOADED_COLLECTION = 1;
    private static final int CATALOG_COLLECTION = 2;

    /** Ordered playlist commands for podcasts and uploaded music. */
    static byte[] orderedPlaylistCommand(String playlistId, boolean uploaded) {
        if (playlistId.isEmpty()) return new byte[0];
        // Auto's ordered WatchPlaylist action distinguishes catalog playlists from uploads.
        byte[] playbackOptions = {OPTION_VALUE_TAG, ORDERED_PLAYBACK};
        byte[] collectionOptions = {OPTION_VALUE_TAG, (byte) (uploaded ? UPLOADED_COLLECTION : CATALOG_COLLECTION)};
        byte[] collectionConfig = message(PLAYLIST_COLLECTION_OPTIONS, collectionOptions);
        byte[] musicConfig = message(PLAYLIST_MUSIC_CONFIG, collectionConfig);
        byte[] supportedConfig = message(PLAYLIST_SUPPORTED_CONFIG, musicConfig);
        return message(WATCH_PLAYLIST,
                message(PLAYLIST_ID, playlistId.getBytes(StandardCharsets.UTF_8)),
                message(PLAYLIST_OPTIONS, playbackOptions),
                supportedConfig);
    }

    private static byte[] message(int field, byte[]... values) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int size = 0;
        for (byte[] value : values) size += value.length;
        writeVarint(output, (field << 3) | WIRE_LENGTH_DELIMITED);
        writeVarint(output, size);
        for (byte[] value : values) output.write(value, 0, value.length);
        return output.toByteArray();
    }

    private static void writeVarint(ByteArrayOutputStream output, int value) {
        while ((value & ~0x7F) != 0) {
            output.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        output.write(value);
    }

    // Refresh after Library changes

    /** Injection point. Remembers the folder subscriptions that Library changes can refresh. */
    public static synchronized void rememberSubscription(FolderReload browserService,
            @Nullable String parentMediaId, Object connection) {
        if (parentMediaId == null) return;
        if (parentMediaId.startsWith(LIBRARY_PREFIX)
                || parentMediaId.startsWith(PODCAST_COLLECTION_PREFIX)
                || parentMediaId.startsWith(HOME_SHELF_PREFIX)
                || SPEED_DIAL_MEDIA_ID.equals(parentMediaId)
                || playlistsTitleMatchMediaIds.contains(parentMediaId)) {
            librarySubscription = new FolderSubscription(browserService, parentMediaId, connection);
        } else if (PODCASTS_MEDIA_ID.equals(parentMediaId)) {
            podcastsSubscription = new FolderSubscription(browserService, parentMediaId, connection);
        } else if (parentMediaId.equals(homeMediaId)) {
            homeSubscription = new FolderSubscription(browserService, parentMediaId, connection);
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
                case "subscription/subscribe":
                case "subscription/unsubscribe":
                    scheduleLibraryRefresh();
            }
        } catch (RuntimeException ex) {
            Logger.printException(() -> "Could not schedule Android Auto Library refresh", ex);
        }
    }

    /** Combines completed Library changes into one delayed refresh. */
    private static synchronized void scheduleLibraryRefresh() {
        if (librarySubscription == null && homeSubscription == null) return;
        // Delay the refresh to give updated playlist artwork time to appear.
        mainHandler.removeCallbacks(REFRESH_LIBRARY);
        mainHandler.postDelayed(REFRESH_LIBRARY, LIBRARY_REFRESH_DELAY_MS);
    }

    private static void refreshLibrary() {
        FolderSubscription library;
        FolderSubscription home;
        // Refresh the current connection if Android Auto reconnected during the delay.
        synchronized (AndroidAutoPatch.class) {
            library = librarySubscription;
            home = homeSubscription;
        }
        // Reloading needs YTM's lock; YTM's result delivery needs this class's lock.
        // Reload outside synchronized to avoid the two threads waiting for each other.
        if (library != null) library.reload(false);
        // Force an Android Auto Home refresh to update the Podcasts tab's cached folders.
        if (home != null) home.reload(true);
    }

    // Browse delivery, root tabs, and the Podcasts tab

    /** Injection point. Recognizes media IDs handled by the patch instead of YTM's decoder. */
    public static boolean isCustomMediaId(@Nullable String mediaId) {
        if (mediaId == null) return false;
        return mediaId.equals(PODCASTS_MEDIA_ID)
                || mediaId.equals(SPEED_DIAL_MEDIA_ID)
                || mediaId.startsWith(LIBRARY_PREFIX)
                || mediaId.startsWith(HOME_SHELF_PREFIX)
                || mediaId.startsWith(PODCAST_COLLECTION_PREFIX);
    }

    /**
     * Backports the missing 9.15 size guard to avoid Binder failures that leave Auto loading.
     * Estimates an item limit from newer YTM's 410 KiB budget and the first ten items' average parcel size.
     */
    @Nullable
    public static List<MediaBrowserCompat.MediaItem> limitBrowseItems(
            @Nullable List<MediaBrowserCompat.MediaItem> items) {
        if (items == null || items.size() <= 10) return items;

        int sampleBytes = 0;
        for (int i = 0; i < 10; i++) {
            Parcel parcel = Parcel.obtain();
            try {
                items.get(i).writeToParcel(parcel, 0);
                sampleBytes += parcel.dataSize();
            } catch (RuntimeException ex) {
                Logger.printException(() -> "Could not measure Android Auto item", ex);
                sampleBytes += 10;
            } finally {
                parcel.recycle();
            }
        }
        int itemLimit = (410 * 1024) / Math.max(1, sampleBytes / 10);
        return itemLimit < items.size() ? new ArrayList<>(items.subList(0, itemLimit)) : items;
    }

    /**
     * Injection point for Android Auto browse results, including the root tabs and the Podcasts tab.
     * For Home, the injected code passes this method's podcast folders to {@link #loadHome}.
     * {@link HomeLoad} sends its combined {@link PreparedItems} through {@link BrowseRequest#patch_deliverBrowseItems}.
     * On reentry, that marker skips podcast caching and another Home load; size checks and YTM's delivery still run.
     */
    @Nullable
    public static synchronized List<MediaBrowserCompat.MediaItem> handleBrowseResult(
            BrowseRequest request, @Nullable List<MediaBrowserCompat.MediaItem> ytmItems) {
        try {
            String parentMediaId = request.patch_getRequestedMediaId();
            if (ROOT_MEDIA_ID.equals(parentMediaId)) {
                return initializeTabs(ytmItems);
            }
            if (PODCASTS_MEDIA_ID.equals(parentMediaId)) {
                return new ArrayList<>(cachedPodcastFolders);
            }
            if (parentMediaId != null && parentMediaId.equals(homeMediaId)
                    && ytmItems != null && !(ytmItems instanceof PreparedItems)) {
                cachePodcastFolders(ytmItems);
                refreshPodcastsAfterHomeLoad();
                // Pass the podcast folders to loadHome(), which starts the phone recommendations request.
                return new ArrayList<>(cachedPodcastFolders);
            }
        } catch (RuntimeException ex) {
            Logger.printException(() -> "handleBrowseResult failure", ex);
        }
        return ytmItems;
    }

    /**
     * Injection point before YTM delivers Home results to Android Auto.
     * Starts {@link HomeLoad} to combine phone recommendations with YTM's podcast folders.
     *
     * @return true if HomeLoad takes over delivery; false to continue YTM's delivery.
     */
    public static synchronized boolean loadHome(BrowseRequest request,
            @Nullable List<MediaBrowserCompat.MediaItem> nativeItems) {
        try {
            if (phoneBrowseClient == null || homeMediaId == null || nativeItems == null
                    || nativeItems instanceof PreparedItems
                    || !homeMediaId.equals(request.patch_getRequestedMediaId())) return false;
            HomeLoad.forHome(request, nativeItems).start();
            return true;
        } catch (RuntimeException ex) {
            Logger.printException(() -> "loadHome failure", ex);
            return false;
        }
    }

    @Nullable
    @GuardedBy("AndroidAutoPatch.class")
    private static List<MediaBrowserCompat.MediaItem> initializeTabs(
            @Nullable List<MediaBrowserCompat.MediaItem> rootTabs) {
        // Do not reuse saved requests or podcast content after the main tabs reload.
        librarySubscription = null;
        podcastsSubscription = null;
        homeSubscription = null;
        mainHandler.removeCallbacks(REFRESH_LIBRARY);
        homeMediaId = null;
        libraryMediaId = null;
        cachedPodcastFolders = Collections.emptyList();

        // Add Podcasts only when YTM supplies Home and Library without a Podcasts tab.
        if (rootTabs == null || rootTabs.size() != 2) return rootTabs;

        homeMediaId = rootTabs.get(0).a();
        libraryMediaId = rootTabs.get(1).a();
        List<MediaBrowserCompat.MediaItem> updatedRootTabs = new ArrayList<>(rootTabs);
        Bundle podcastsExtras = new Bundle();
        podcastsExtras.putInt(CONTENT_STYLE_BROWSABLE_HINT, CONTENT_STYLE_LIST);
        podcastsExtras.putInt(CONTENT_STYLE_PLAYABLE_HINT, CONTENT_STYLE_LIST);
        podcastsExtras.putBoolean("com.google.android.apps.youtube.music.mediabrowser.IS_RECOMMENDED_PODCASTS", true);
        MediaDescriptionCompat podcastsDescription = new MediaDescriptionCompat(
                PODCASTS_MEDIA_ID,
                ResourceUtils.getString(PODCASTS_TITLE_RESOURCE_NAME),
                null, null, null, Uri.parse(LibraryFolder.PODCASTS.artworkUri()), podcastsExtras, null);
        updatedRootTabs.add(1, new MediaBrowserCompat.MediaItem(
                podcastsDescription, MediaBrowserCompat.MediaItem.FLAG_BROWSABLE));
        return updatedRootTabs;
    }

    @GuardedBy("AndroidAutoPatch.class")
    private static void refreshPodcastsAfterHomeLoad() {
        FolderSubscription subscription = podcastsSubscription;
        if (subscription == null) return;
        // New Home results can change the podcast folder IDs.
        // Always queue the reload so handleBrowseResult releases its lock first.
        mainHandler.post(() -> {
            synchronized (AndroidAutoPatch.class) {
                // Reloading the main tabs or reconnecting replaces the saved Podcasts request.
                if (podcastsSubscription != subscription) return;
            }
            subscription.reload(false);
        });
    }

    @GuardedBy("AndroidAutoPatch.class")
    private static void cachePodcastFolders(List<MediaBrowserCompat.MediaItem> homeItems) {
        List<MediaBrowserCompat.MediaItem> folders = new ArrayList<>();
        // Some podcast folders have no layout hint; include them if they are browsable.
        for (MediaBrowserCompat.MediaItem item : homeItems) {
            // YTM's upgrade prompt is also browsable; exclude it explicitly.
            if (!item.b() || UPGRADE_PROMPT_MEDIA_ID.equals(item.a())) continue;
            folders.add(item);
        }
        cachedPodcastFolders = folders;
    }

    // Home: recommendations, Speed dial, and expanded groups

    /** Home spans multiple phone responses; both its previews and expanded shelves need pagination. */
    private static final class HomeLoad extends Load {
        private enum Mode { HOME, SHELF, SPEED_DIAL }

        private final Mode mode;
        private final String browseId;
        private final String shelfTitle;
        private final List<MediaBrowserCompat.MediaItem> podcastFolders;
        @GuardedBy("this")
        private final List<BrowseItem> collected = new ArrayList<>();
        private final Set<String> continuations = new HashSet<>();

        private HomeLoad(BrowseRequest request, Mode mode, String shelfTitle,
                         List<MediaBrowserCompat.MediaItem> podcastFolders) {
            super(request);
            this.mode = mode;
            browseId = mode == Mode.SPEED_DIAL ? SPEED_DIAL_BROWSE_ID : "FEmusic_home";
            this.shelfTitle = shelfTitle;
            this.podcastFolders = podcastFolders;
        }

        static HomeLoad forHome(BrowseRequest request, List<MediaBrowserCompat.MediaItem> podcastFolders) {
            return new HomeLoad(request, Mode.HOME, "", new ArrayList<>(podcastFolders));
        }

        static HomeLoad forShelf(BrowseRequest request, String mediaId) {
            // An expanded shelf's media ID contains its title. Fetch phone Home again and select that shelf.
            String shelfTitle = Uri.decode(mediaId.substring(HOME_SHELF_PREFIX.length()));
            return new HomeLoad(request, Mode.SHELF, shelfTitle, Collections.emptyList());
        }

        static HomeLoad forSpeedDial(BrowseRequest request) {
            return new HomeLoad(request, Mode.SPEED_DIAL, "", Collections.emptyList());
        }

        @Override void load() { requestPage(""); }

        private void requestPage(String continuation) {
            requestPage(browseId, continuation, response -> {
                HomePage page = homePage(response.patch_responseBytes());
                synchronized (this) { collected.addAll(page.items()); }
                if (page.continuation().isEmpty() || !continuations.add(page.continuation())) {
                    finishCollected();
                } else {
                    requestPage(page.continuation());
                }
            });
        }

        private void finishCollected() {
            if (finished.get()) return;
            try {
                List<BrowseItem> snapshot;
                synchronized (this) {
                    snapshot = new ArrayList<>(collected);
                }

                finish(convert(snapshot));
            } catch (Exception ex) {
                Logger.printException(() -> "Could not convert Android Auto Home items", ex);
                finish(Collections.emptyList());
            }
        }

        // If a later page fails or times out, return the shelves collected so far.
        @Override void timedOut() { finishCollected(); }

        private List<MediaBrowserCompat.MediaItem> convert(List<BrowseItem> items) {
            return switch (mode) {
                case HOME -> homeMediaItems(items);
                case SHELF -> {
                    items.removeIf(item -> !shelfTitle.equals(item.shelfTitle()));
                    yield homePlayableItems(items, CONTENT_STYLE_GRID);
                }
                case SPEED_DIAL -> homePlayableItems(items, CONTENT_STYLE_LIST);
            };
        }

        @Override void deliver(List<MediaBrowserCompat.MediaItem> items) {
            if (mode != Mode.HOME) {
                super.deliver(items);
                return;
            }
            PreparedItems home = new PreparedItems();
            if (current(client)) {
                home.addAll(items);
                // Phone music recommendations and Auto podcast folders have no shared ranking; keep each source's order.
                home.addAll(podcastFolders);
            }
            request.patch_deliverBrowseItems(home);
        }
    }

    private static PreparedItems homeMediaItems(List<BrowseItem> phoneItems) {
        PreparedItems homeItems = new PreparedItems(CONTENT_STYLE_GRID);
        Map<String, List<BrowseItem>> shelves = new LinkedHashMap<>();
        for (var item : phoneItems) shelves.computeIfAbsent(item.shelfTitle(), ignored -> new ArrayList<>()).add(item);
        for (var shelf : shelves.entrySet()) {
            String shelfTitle = shelf.getKey();
            List<BrowseItem> shelfItems = shelf.getValue();
            if (shelfTitle.isEmpty()) {
                homeItems.addAll(homePlayableItems(shelfItems, CONTENT_STYLE_GRID));
                continue;
            }
            boolean speedDial = SPEED_DIAL_BROWSE_ID.equals(shelfItems.get(0).shelfBrowseId());
            String shelfMediaId = speedDial ? SPEED_DIAL_MEDIA_ID : HOME_SHELF_PREFIX + Uri.encode(shelfTitle);
            Bundle extras = new Bundle();
            extras.putInt(CONTENT_STYLE_SINGLE_ITEM_HINT, CONTENT_STYLE_LIST);
            extras.putInt(CONTENT_STYLE_BROWSABLE_HINT, CONTENT_STYLE_LIST);
            extras.putInt(CONTENT_STYLE_PLAYABLE_HINT, speedDial ? CONTENT_STYLE_LIST : CONTENT_STYLE_GRID);
            homeItems.add(new MediaBrowserCompat.MediaItem(new MediaDescriptionCompat(
                    shelfMediaId, shelfTitle, null, null, null, null, extras, null),
                    MediaBrowserCompat.MediaItem.FLAG_BROWSABLE));
            // Keep recommendations playable from Android Auto Home without opening each shelf.
            var preview = homePlayableItems(shelfItems, CONTENT_STYLE_GRID);
            homeItems.addAll(preview.subList(0, Math.min(3, preview.size())));
        }
        return homeItems;
    }

    private static PreparedItems homePlayableItems(List<BrowseItem> phoneItems, int playableStyle) {
        return playableItems(phoneItems, playableStyle, false);
    }

    // Library: categories (including Last played), uploads, and episodes from saved podcast shows

    private static final class LibraryLoad extends Load {
        final String mediaId;

        LibraryLoad(BrowseRequest request, String mediaId) {
            super(request);
            this.mediaId = mediaId;
        }

        @Override void load() {
            boolean podcastPage = mediaId.startsWith(PODCAST_COLLECTION_PREFIX);
            LibraryFolder folder = podcastPage ? null : LibraryFolder.valueOf(mediaId.substring(LIBRARY_PREFIX.length()));
            String browseId = podcastPage ? Uri.decode(mediaId.substring(PODCAST_COLLECTION_PREFIX.length())) : folder.browseId;
            requestPage(browseId, "", response -> {
                var phoneItems = libraryItems(response.patch_responseBytes());
                finish(podcastPage ? playableItems(phoneItems, CONTENT_STYLE_LIST) : libraryMediaItems(folder, phoneItems));
            });
        }
    }

    private static PreparedItems libraryMediaItems(LibraryFolder folder,
                                                   List<BrowseItem> phoneItems) {
        if (folder == LibraryFolder.PODCASTS) return podcastFolders(phoneItems);
        PreparedItems items = new PreparedItems(folder == LibraryFolder.LAST_PLAYED ? CONTENT_STYLE_LIST : CONTENT_STYLE_GRID);
        LibraryFolder uploads = switch (folder) {
            case ALBUMS -> LibraryFolder.UPLOADED_ALBUMS;
            case SONGS -> LibraryFolder.UPLOADED_SONGS;
            case ARTISTS -> LibraryFolder.UPLOADED_ARTISTS;
            default -> null;
        };
        if (uploads != null) items.add(uploads.mediaItem());
        items.addAll(playableItems(phoneItems, items.playableStyle));
        return items;
    }

    private static PreparedItems podcastFolders(List<BrowseItem> phoneItems) {
        PreparedItems folders = new PreparedItems(CONTENT_STYLE_LIST);
        Set<String> seenBrowseIds = new HashSet<>();
        for (var item : phoneItems) {
            String browseId = new ProtoMessage(item.tapCommandBytes())
                    .messageField(BROWSE).stringField(BROWSE_ID);
            if (browseId.isEmpty() || !seenBrowseIds.add(browseId)) continue;
            // Phone show IDs return empty pages through Auto's loader. Request their phone episode lists instead.
            folders.add(PODCAST_COLLECTION_PREFIX + Uri.encode(browseId),
                    item.title(), item.subtitle(), item.artworkUrl(), false);
        }
        return folders;
    }

    // Shared media items and playback commands

    /** Converted Auto results; delivery hooks recognize this type to avoid starting another load. */
    static final class PreparedItems extends ArrayList<MediaBrowserCompat.MediaItem> {
        final int playableStyle;

        PreparedItems() { this(CONTENT_STYLE_LIST); }
        PreparedItems(int playableStyle) { this.playableStyle = playableStyle; }

        void add(String mediaId, String title, String subtitle, String artworkUrl, boolean playable) {
            add(mediaId, title, subtitle, artworkUrl, playable, "");
        }

        void add(String mediaId, String title, String subtitle, String artworkUrl, boolean playable, String groupTitle) {
            if (mediaId.length() > MAX_MEDIA_ID_LENGTH) return;
            Bundle extras = new Bundle();
            extras.putInt(CONTENT_STYLE_SINGLE_ITEM_HINT, playable ? playableStyle : CONTENT_STYLE_LIST);
            if (!groupTitle.isEmpty()) extras.putString(CONTENT_STYLE_GROUP_TITLE_HINT, groupTitle);
            MediaDescriptionCompat description = new MediaDescriptionCompat(
                    mediaId, title, subtitle, null, null, artworkUrl.isEmpty() ? null : Uri.parse(artworkUrl), extras, null);
            add(new MediaBrowserCompat.MediaItem(description,
                    playable ? MediaBrowserCompat.MediaItem.FLAG_PLAYABLE : MediaBrowserCompat.MediaItem.FLAG_BROWSABLE));
        }
    }

    private static PreparedItems playableItems(List<BrowseItem> phoneItems,
                                              int playableStyle) {
        return playableItems(phoneItems, playableStyle, true);
    }

    private static PreparedItems playableItems(List<BrowseItem> phoneItems,
                                              int playableStyle, boolean showShelfTitles) {
        PreparedItems items = new PreparedItems(playableStyle);
        Set<String> seenMediaIds = new HashSet<>();
        for (BrowseItem item : phoneItems) {
            byte[] playCommandBytes = item.tapStartsPlayback() ? item.tapCommandBytes() : item.playCommandBytes();
            boolean preserveCommand = item.tapStartsPlayback();
            String browseId = new ProtoMessage(item.tapCommandBytes())
                    .messageField(BROWSE).stringField(BROWSE_ID);
            if (isUploadedCollection(browseId) && playCommandBytes.length <= MAX_COMMAND_BYTES) {
                String playlistId = new ProtoMessage(playCommandBytes).messageField(WATCH_PLAYLIST).stringField(PLAYLIST_ID);
                if (!playlistId.isEmpty()) {
                    playCommandBytes = orderedPlaylistCommand(playlistId, true);
                    preserveCommand = true;
                }
            }
            String mediaId = playbackMediaId(playCommandBytes, preserveCommand);
            if (mediaId == null || !seenMediaIds.add(mediaId)) continue;
            items.add(mediaId, item.title(), item.subtitle(), item.artworkUrl(), true, showShelfTitles ? item.shelfTitle() : "");
        }
        return items;
    }

    /** @return the Auto media ID that plays this command, or null if the command cannot be played. */
    @Nullable
    private static String playbackMediaId(byte[] playCommandBytes, boolean preserveCommand) {
        if (playCommandBytes.length > MAX_COMMAND_BYTES) return null;
        var playCommand = new ProtoMessage(playCommandBytes);
        if (!isPlayable(playCommand)) return null;
        String playlistId = playCommand.messageField(WATCH_PLAYLIST).stringField(PLAYLIST_ID);
        // A menu's playlist action may shuffle; use ordered playback for albums and playlists.
        // Preserve YTM's "Start radio" action, which plays a generated mix.
        if (!preserveCommand && !playlistId.isEmpty() && !playlistId.startsWith("RD")) {
            return createPlaylistMediaId(playlistId);
        }
        return encodeCommand(playCommandBytes);
    }

    /** Replaced during patching with YTM's command parser and Android Auto media ID encoder. */
    private static String encodeCommand(byte[] command) {
        throw new UnsupportedOperationException("Android Auto command bridge was not installed");
    }

    // Request timeouts and cancellation

    private static boolean current(@Nullable PhoneBrowseClient client) {
        return client != null && client == phoneBrowseClient;
    }

    /** Each load owns its pending request and completes once, including when it times out. */
    abstract static class Load {
        final BrowseRequest request;
        @Nullable
        final PhoneBrowseClient client;
        final long loadNumber = browseLoadCounter.incrementAndGet();
        final FolderDelivery folderDelivery;
        final AtomicBoolean finished = new AtomicBoolean();
        @Nullable
        volatile ListenableFuture<?> pending;
        final Runnable timeout = this::timedOut;

        Load(BrowseRequest request) {
            this.request = request;
            client = phoneBrowseClient;
            folderDelivery = getFolderDelivery(request);
        }

        final void start() {
            mainHandler.postDelayed(timeout, DEADLINE_MS);
            BACKGROUND_EXECUTOR.execute(() -> {
                try { load(); }
                catch (Exception ex) {
                    Logger.printException(() -> "Could not load Android Auto content", ex);
                    timedOut();
                }
            });
        }

        abstract void load();

        final void requestPage(String browseId, String continuation, Consumer<PhoneBrowseResponse> receive) {
            if (finished.get()) return;
            try {
                if (!current(client)) {
                    finish(Collections.emptyList());
                    return;
                }
                var future = continuation.isEmpty()
                        ? client.patch_requestBrowse(browseId, BACKGROUND_EXECUTOR)
                        : client.patch_requestBrowseContinuation(continuation, BACKGROUND_EXECUTOR);
                pending = future;
                if (finished.get()) {
                    future.cancel(true);
                    return;
                }

                future.addListener(() -> {
                    if (finished.get()) return;
                    try {
                        PhoneBrowseResponse response = future.get();
                        receive.accept(response);
                    } catch (Exception ex) {
                        Logger.printException(() -> "Could not read Android Auto page", ex);
                        timedOut();
                    }
                }, BACKGROUND_EXECUTOR);
            } catch (Exception ex) {
                Logger.printException(() -> "Could not request Android Auto page", ex);
                timedOut();
            }
        }

        void finish(List<MediaBrowserCompat.MediaItem> items) {
            if (!finished.compareAndSet(false, true)) return;
            mainHandler.removeCallbacks(timeout);
            var future = pending;
            if (future != null) future.cancel(true);
            mainHandler.post(() -> folderDelivery.deliver(loadNumber,
                    () -> deliver(current(client) ? items : Collections.emptyList())));
        }

        void timedOut() { finish(Collections.emptyList()); }
        void deliver(List<MediaBrowserCompat.MediaItem> items) {
            request.patch_deliverBrowseItems(items);
        }
    }

    // Search results

    static List<MediaBrowserCompat.MediaItem> createPlayableSearchResults(List<SearchResult> results) {
        PreparedItems playableResults = new PreparedItems(CONTENT_STYLE_LIST);
        Map<ResultCategory, List<SearchResult>> resultsByCategory = new LinkedHashMap<>();
        // Order categories by their first result; keep each category together so Auto shows one heading.
        for (var result : results) {
            resultsByCategory
                    .computeIfAbsent(result.category(), ignored -> new ArrayList<>())
                    .add(result);
        }
        Set<String> seenMediaIds = new HashSet<>();
        for (var categoryResults : resultsByCategory.values()) {
            for (var searchResult : categoryResults) {
                String mediaId = playbackMediaId(searchResult.playCommandBytes(),
                        searchResult.preservePlaybackCommand());
                if (mediaId == null || !seenMediaIds.add(mediaId)) continue;
                playableResults.add(mediaId, searchResult.title(), searchResult.subtitle(), searchResult.artworkUrl(),
                        true, ResourceUtils.getString(searchResult.category().titleResource));
            }
        }
        return playableResults;
    }

}
