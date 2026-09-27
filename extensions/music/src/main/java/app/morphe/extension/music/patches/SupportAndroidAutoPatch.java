/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3341
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches;

import android.media.session.MediaSession;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;

/**
 * Adds YT Music support in Android Auto by intercepting requests for the Playlists folder,
 * loading playlists from the phone Library, and filling a Podcasts tab from Android Auto Home.
 *
 * <p>During service initialization, {@link #setPhoneBrowseClient} saves the YTM object that requests
 * phone Library and playlist data. {@link #rememberPlaylistsTitleMatch} identifies Playlists in
 * Android Auto's Library by its translated title.
 *
 * <p>When Android Auto opens Playlists, {@link #handleAndroidAutoPlaylists} requests the phone Library.
 * {@link #requestLibraryPage} follows Library pagination and collects playlists with their titles and artwork.
 * {@link #deliverAndroidAutoPlaylists} returns them when loading finishes, fails, or times out.
 *
 * <p>Selecting a playlist calls {@link #handlePlayFromMediaId}. {@link PlaylistPlaybackRequest}
 * fetches its songs and Play button, then asks YTM to start playback if it contains songs.
 *
 * <p>{@link #handleAndroidAutoBrowseResult} adds Podcasts alongside Home and Library, then fills it with
 * the podcast lists YTM returns for Android Auto Home. Each Home result calls
 * {@link #refreshPodcastsAfterHomeLoad} to update Podcasts if it has been opened.
 *
 * <p>Library changes include creating, deleting, and editing playlists, liking/unliking songs,
 * and saving/removing playlists or shows. Successful changes reach {@link #scheduleLibraryRefresh}
 * through {@link #watchLibraryChange} or YTM's success callbacks.
 * {@link #refreshAndroidAutoLibrary} repeats the Playlists and Home requests saved by
 * {@link #rememberAndroidAutoSubscription}. Updated Home lists also refresh Podcasts.
 */
@SuppressWarnings("unused")
public final class SupportAndroidAutoPatch {
    private static final String PHONE_LIBRARY_BROWSE_ID = "FEmusic_library_landing";
    private static final String LIKED_MUSIC_BROWSE_ID = "VLLM";
    private static final String EPISODES_FOR_LATER_BROWSE_ID = "VLSE";
    // IDs with this prefix contain a playlist page ID; request its playback command when selected.
    private static final String DEFERRED_PLAYLIST_MEDIA_ID_PREFIX = "morphe:aa:playlist:";
    private static final String PLAYLISTS_TITLE_RESOURCE_NAME = "library_playlists_shelf_title";
    // Return collected playlists when this timeout expires.
    private static final int ANDROID_AUTO_PLAYLISTS_TIMEOUT_MILLISECONDS = 30_000;
    private static final int SELECTED_PLAYLIST_LOAD_TIMEOUT_MILLISECONDS = 30_000;
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
    // Selecting music or pressing Pause/Stop changes this number; ignore pending requests with older numbers.
    private static final AtomicLong playRequestGeneration = new AtomicLong();
    // Give each Library load a number to distinguish earlier requests from later requests.
    private static final AtomicLong playlistsLoadCounter = new AtomicLong();
    // Remember which Library load last updated each folder on each Android Auto connection.
    @GuardedBy("itself")
    private static final WeakHashMap<Object, Map<String, PlaylistsFolderDelivery>>
            playlistsFolderDeliveries = new WeakHashMap<>();
    // Android Auto's saved request to receive updates for the Playlists folder.
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
    @GuardedBy("SupportAndroidAutoPatch.class")
    private static List<MediaBrowserCompat.MediaItem> cachedAndroidAutoPodcastFolders =
            Collections.emptyList();

    /** YTM's object for sending phone Library and playlist requests, reused to supply Android Auto. */
    public interface PhoneBrowseClient {
        @NonNull ListenableFuture<PhoneBrowseResponse> patch_requestBrowse(
                @NonNull String browseId, @NonNull Executor executor);
        @NonNull ListenableFuture<PhoneBrowseResponse> patch_requestLibraryPagination(
                @NonNull Object paginationCommand, @NonNull Executor executor);
    }

    /**
     * Data returned by a request for the phone Library or a playlist's contents.
     * The first Library response and playlist contents use TabRenderer data, then sections containing the items.
     * Later Library pages use {@link #patch_getPaginatedLibraryGrid} instead.
     * TabRenderer describes the phone page, not Android Auto's Home/Library/Podcasts tabs.
     */
    public interface PhoneBrowseResponse {
        // Wrappers for the TabRenderer data containing the first Library result or playlist contents.
        @NonNull Iterable<PhoneBrowseTab> patch_getTabs();
        // More Library items returned by pagination.
        @Nullable GridRenderer patch_getPaginatedLibraryGrid();
        // Command from the Play button above the playlist's songs, encoded as an Android Auto media ID.
        @Nullable String patch_getPlaylistPlayButtonMediaId();
    }

    /** YTM's object for extracting sections from TabRenderer data in a phone Library or playlist response. */
    public interface PhoneBrowseTab {
        @Nullable SectionList patch_getSectionList();
    }

    /** Groups of Library items ({@link GridRenderer}) or playlist songs ({@link PlaylistContents}) in a phone response. */
    public interface SectionList {
        @NonNull Iterable<?> patch_getContents();
    }

    /** Library items and the commands to request more of them. */
    public interface GridRenderer {
        // Includes artists and podcasts as well as playlists; filter before returning playlists to Android Auto.
        @NonNull Iterable<?> patch_getItems();
        // YTM's pagination commands: NEXT requests the next Library page; RELOAD refreshes the list.
        @NonNull Iterable<?> patch_getPaginationCommands();
    }

    /** Playlist contents include songs and the "Add a song" button. */
    public interface PlaylistContents {
        @NonNull Iterable<PhoneBrowseItem> patch_getItems();
    }

    /** A request from Android Auto to load content, such as its main tabs, Playlists, or a podcast list. */
    public interface AndroidAutoBrowseRequest {
        @Nullable String patch_getRequestedMediaId();
        // The Android Auto connection for this request, or null if unknown.
        @Nullable Object patch_getBrowserConnection();
        /**
         * Sends the list through YTM. {@link SupportAndroidAutoPatch#handleAndroidAutoBrowseResult}
         * can add the Podcasts tab or supply its contents before Android Auto receives the list.
         * YTM may remove items to keep the returned list within its byte limit.
         */
        void patch_deliverAndroidAutoItems(
                @NonNull List<MediaBrowserCompat.MediaItem> androidAutoItems);
    }

    /** Refreshes Home, Playlists, or Podcasts without reconnecting Android Auto. */
    public interface AndroidAutoFolderReload {
        void patch_reloadFolder(
                @NonNull String parentMediaId, @NonNull Object connection, @Nullable Bundle options);
    }

    /** Methods installed on YTM's {@link MediaSession.Callback} to use its playback thread. */
    public interface PlaybackCallback {
        @Nullable Handler patch_getCallbackHandler();
    }

    /** YTM's item type for Library content, playlist songs, and the "Add a song" button. */
    public interface PhoneBrowseItem {
        /**
         * Returns a playlist page ID, or null if none is found or the item's commands identify different playlists.
         * A command that does not open a page makes YTM's converter throw;
         * {@link SupportAndroidAutoPatch#collectPlaylistsFromGrid} skips that item.
         */
        @Nullable String patch_getPlaylistBrowseId();
        /**
         * YTM's media ID for the item's command. {@link #patch_hasPlayableVideoId} checks whether it identifies a song.
         */
        @Nullable String patch_getCommandMediaId();
        // YTM calls a song's identifier a video ID, even when only audio is played.
        boolean patch_hasPlayableVideoId();
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
     * Discard refreshes saved by the previous service so they cannot use its old Android Auto connection.
     */
    public static synchronized void setPhoneBrowseClient(@NonNull PhoneBrowseClient client) {
        phoneBrowseClient = client;
        playlistsSubscription = null;
        podcastsSubscription = null;
        homeSubscription = null;
        refreshHandler.removeCallbacks(REFRESH_LIBRARY);
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
     * <p>{@link #requestLibraryPage} starts loading the Library. The timeout can independently
     * call {@link #deliverAndroidAutoPlaylists} while pagination is still running.
     * <p>On failure or timeout, return the playlists collected so far, or an empty list if none.
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
            Object connection = androidAutoRequest.patch_getBrowserConnection();
            PlaylistsFolderDelivery folderDelivery;
            synchronized (playlistsFolderDeliveries) {
                if (connection == null) {
                    // Without the connection, there is no way to identify other requests for this same folder.
                    folderDelivery = new PlaylistsFolderDelivery();
                } else {
                    // Reopening Playlists or refreshing after Library changes can start another load
                    // before this one finishes.
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
     * Loads the Library one page at a time. Background listeners read the first response with
     * {@link #appendInitialLibraryPlaylists} and later responses with {@link #appendPaginatedLibraryPlaylists}.
     * Both collect playlists through {@link #collectPlaylistsFromGrid} and return a pagination command if available.
     * A command requests the next page; otherwise {@link #deliverAndroidAutoPlaylists} returns the collected list.
     * Failure and timeout also return the playlists collected so far.
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
            SectionList sectionList = tab.patch_getSectionList();
            if (sectionList == null) continue;
            for (Object sectionContent : sectionList.patch_getContents()) {
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
            @Nullable String singleTapBrowseId, @Nullable String doubleTapBrowseId) {
        if (singleTapBrowseId != null && !singleTapBrowseId.startsWith("VL")) singleTapBrowseId = null;
        if (doubleTapBrowseId != null && !doubleTapBrowseId.startsWith("VL")) doubleTapBrowseId = null;
        if (singleTapBrowseId == null) return doubleTapBrowseId;
        if (doubleTapBrowseId == null || singleTapBrowseId.equals(doubleTapBrowseId)) return singleTapBrowseId;
        return null;
    }

    /**
     * Uses titles and artwork supplied by the Library to avoid requesting every playlist's songs
     * while loading Playlists in Android Auto.
     */
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
        // Read titles and artwork before locking so a slow read cannot block timeout handling.
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
     * Returns the collected playlists as items Android Auto can play.
     * {@link PlaylistsFolderLoad#takePlaylistsForDelivery} stops collection and prevents completion and timeout
     * from both returning this request's playlists.
     * {@link PlaylistsFolderDelivery#deliver} prevents an older list from replacing a newer list already returned.
     */
    private static void deliverAndroidAutoPlaylists(
            AndroidAutoBrowseRequest androidAutoRequest,
            PlaylistsFolderLoad load, String logReason) {
        List<LibraryPlaylist> collectedPlaylists = load.takePlaylistsForDelivery();
        if (collectedPlaylists == null) return;

        List<MediaBrowserCompat.MediaItem> androidAutoPlaylistItems =
                new ArrayList<>(collectedPlaylists.size());
        for (LibraryPlaylist playlist : collectedPlaylists) {
            try {
                androidAutoPlaylistItems.add(createDeferredPlaylistItem(playlist));
            } catch (RuntimeException ex) {
                Logger.printException(() -> "Could not build an Android Auto playlist item", ex);
            }
        }
        load.folderDelivery.deliver(load.loadNumber, androidAutoRequest, androidAutoPlaylistItems, logReason);
    }

    /**
     * Marks the playlist playable so tapping it starts playback instead of opening a song list.
     * Stores its page ID (VL...) for {@link #handlePlayFromMediaId} to fetch the playback command on selection.
     */
    private static MediaBrowserCompat.MediaItem createDeferredPlaylistItem(
            LibraryPlaylist playlist) {
        MediaDescriptionCompat description = new MediaDescriptionCompat(
                DEFERRED_PLAYLIST_MEDIA_ID_PREFIX + playlist.playlistBrowseId,
                playlist.title, playlist.subtitle, null, null, playlist.artworkUri,
                null, null);
        return new MediaBrowserCompat.MediaItem(
                description, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE);
    }

    /**
     * Stores playlists collected for one Android Auto Playlists request.
     * {@link #takePlaylistsForDelivery} stops collection before Android Auto items are built.
     */
    private static final class PlaylistsFolderLoad {
        // Use the same YTM object throughout pagination, even if MusicBrowserService restarts.
        private final PhoneBrowseClient phoneBrowseClient;
        private final long loadNumber = playlistsLoadCounter.incrementAndGet();
        private final PlaylistsFolderDelivery folderDelivery;
        @GuardedBy("this")
        private final List<LibraryPlaylist> libraryPlaylists = new ArrayList<>();
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
        private synchronized List<LibraryPlaylist> takePlaylistsForDelivery() {
            if (deliveryPreparationStarted) return null;
            deliveryPreparationStarted = true;
            return new ArrayList<>(libraryPlaylists);
        }
    }

    /** Shared by loads for the same Playlists folder and Android Auto connection. */
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
                // Reopening Playlists starts a new request.
                Logger.printException(() -> "Could not deliver Android Auto playlists", ex);
            }
        }
    }

    private record LibraryPlaylist(
            String playlistBrowseId, String title, String subtitle, Uri artworkUri) {
    }

    // Refresh after Library changes

    /**
     * Injection point. Save Android Auto's requests for Playlists, Home, and Podcasts for later refreshes.
     */
    public static synchronized void rememberAndroidAutoSubscription(
            @NonNull AndroidAutoFolderReload browserService,
            @Nullable String parentMediaId,
            @NonNull Object connection) {
        if (parentMediaId == null) return;
        if (playlistsTitleMatchMediaIds.contains(parentMediaId)) {
            playlistsSubscription = new AndroidAutoSubscription(browserService, parentMediaId, connection);
        } else if (PODCASTS_MEDIA_ID.equals(parentMediaId)) {
            podcastsSubscription = new AndroidAutoSubscription(browserService, parentMediaId, connection);
        } else if (parentMediaId.equals(androidAutoHomeMediaId)) {
            homeSubscription = new AndroidAutoSubscription(browserService, parentMediaId, connection);
        }
    }

    /**
     * Injection point. Schedule an Android Auto refresh after a successful Library change.
     * {@link #scheduleLibraryRefresh} combines changes made close together into one refresh.
     */
    public static void watchLibraryChange(@Nullable ListenableFuture<?> changeResult) {
        if (changeResult == null) return;
        synchronized (SupportAndroidAutoPatch.class) {
            if (playlistsSubscription == null && homeSubscription == null) return;
        }
        try {
            changeResult.addListener(() -> {
                try {
                    // The request has finished; get() throws if it failed.
                    changeResult.get();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (ExecutionException | RuntimeException ex) {
                    Logger.printException(() -> "Library change failed", ex);
                    return;
                }
                scheduleLibraryRefresh();
            }, BACKGROUND_EXECUTOR);
        } catch (RuntimeException ex) {
            Logger.printException(() -> "Could not observe Library change", ex);
        }
    }

    /** Injection point. Combine completed Library changes into one delayed refresh. */
    public static synchronized void scheduleLibraryRefresh() {
        if (playlistsSubscription == null && homeSubscription == null) return;
        // Wait for updated playlist artwork before refreshing.
        refreshHandler.removeCallbacks(REFRESH_LIBRARY);
        refreshHandler.postDelayed(REFRESH_LIBRARY, LIBRARY_REFRESH_DELAY_MILLISECONDS);
    }

    /**
     * Refreshes Playlists and Home through {@link AndroidAutoSubscription#reload}.
     * The repeated Playlists request reaches {@link #handleAndroidAutoPlaylists}, which fetches the Library again.
     * Updated Home content reaches {@link #handleAndroidAutoBrowseResult} and supplies the changed podcast lists.
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
     * Android Auto's request to receive updates for Playlists, Home, or Podcasts.
     * Saves the list ID and connection needed to refresh its contents.
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
     * Injection point. Add the Podcasts tab.
     * {@link #initializeAndroidAutoTabs} inserts Podcasts into YTM's Home/Library tab list.
     * {@link #cacheAndroidAutoPodcastFolders} keeps the podcast lists returned for Home;
     * these become the contents of Podcasts. {@link #refreshPodcastsAfterHomeLoad} refreshes
     * a previously opened Podcasts tab whenever new Home results arrive.
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
        cachedAndroidAutoPodcastFolders = Collections.emptyList();
        // Add Podcasts only when YTM supplies Home and Library without a Podcasts tab.
        if (rootTabs == null || rootTabs.size() != 2) return rootTabs;

        androidAutoHomeMediaId = rootTabs.get(0).a();
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

    // Play a selected playlist

    /**
     * Injection point. Convert this patch's playlist media ID into YTM's command to start playback.
     *
     * <p>Empty playlists, request errors, timeouts, and missing Play commands are logged; playback is left unchanged.
     *
     * @return true for this patch's media IDs, including failed or pending requests;
     *         false for YTM's own IDs. YTM cannot decode this patch's IDs.
     */
    public static boolean handlePlayFromMediaId(
            @NonNull MediaSession.Callback callback, @Nullable String mediaId,
            @Nullable Bundle extras) {
        if (mediaId == null || !mediaId.startsWith(DEFERRED_PLAYLIST_MEDIA_ID_PREFIX)) {
            // YTM's own selections also cancel pending playlist playback.
            playRequestGeneration.incrementAndGet();
            return false;
        }
        long requestGeneration = playRequestGeneration.incrementAndGet();
        String playlistBrowseId = mediaId.substring(DEFERRED_PLAYLIST_MEDIA_ID_PREFIX.length());
        try {
            new PlaylistPlaybackRequest(callback, playlistBrowseId, requestGeneration).start(extras);
        } catch (Exception ex) {
            Logger.printException(() -> "Could not start Android Auto playlist request: " +
                    playlistBrowseId, ex);
        }
        return true;
    }

    /**
     * Injection point. Prevent a pending playlist selection from starting playback after Pause/Stop.
     */
    public static void cancelPendingPlaylistPlayback() {
        // YTM cannot cancel a playback command it has not received yet.
        playRequestGeneration.incrementAndGet();
    }

    /**
     * Loads one selected playlist and asks YTM to play it if it contains songs.
     * {@link #start} calls YTM's request method on the caller's thread; the completed response
     * runs through {@link #readResponse} on {@link SupportAndroidAutoPatch#BACKGROUND_EXECUTOR}.
     * {@link #postToPlaybackThread} runs playback on YTM's playback thread.
     * The timeout runs on the main thread and discards responses that arrive too late.
     */
    private static final class PlaylistPlaybackRequest {
        private final MediaSession.Callback callback;
        // Same object as callback; the added interface exposes its playback thread.
        private final PlaybackCallback callbackAccess;
        private final String playlistBrowseId;
        // Reject this selection after another selection or Pause/Stop.
        private final long requestGeneration;
        // Discard this selection if YTM replaces the original request client.
        private final PhoneBrowseClient browseClientAtStart;

        private PlaylistPlaybackRequest(
                MediaSession.Callback callback, String playlistBrowseId, long requestGeneration) {
            this.callback = callback;
            this.callbackAccess = (PlaybackCallback) callback;
            this.playlistBrowseId = playlistBrowseId;
            this.requestGeneration = requestGeneration;
            this.browseClientAtStart = phoneBrowseClient;
        }

        private void start(@Nullable Bundle extras) {
            if (playlistBrowseId.isEmpty() || browseClientAtStart == null) {
                Logger.printDebug(() -> "Could not resolve selected Android Auto playlist");
                return;
            }
            Handler callbackHandler = callbackAccess.patch_getCallbackHandler();
            if (callbackHandler == null) {
                Logger.printDebug(() -> "Android Auto playback callback is no longer active");
                return;
            }
            // Keep a separate copy of the playback options while the playlist loads.
            Bundle playbackExtras = extras == null ? null : new Bundle(extras);
            // The timeout discards late responses but lets a response already being read finish.
            AtomicBoolean waitingForResponse = new AtomicBoolean(true);
            Utils.runOnMainThreadDelayed(() -> {
                if (!waitingForResponse.compareAndSet(true, false)) return;
                if (requestGeneration == playRequestGeneration.get()) {
                    Logger.printDebug(() -> "Selected Android Auto playlist request timed out");
                }
            }, SELECTED_PLAYLIST_LOAD_TIMEOUT_MILLISECONDS);
            try {
                ListenableFuture<PhoneBrowseResponse> future =
                        browseClientAtStart.patch_requestBrowse(playlistBrowseId, BACKGROUND_EXECUTOR);
                future.addListener(() -> {
                    if (!waitingForResponse.compareAndSet(true, false)) return;
                    readResponse(future, callbackHandler, playbackExtras);
                }, BACKGROUND_EXECUTOR);
            } catch (RuntimeException ex) {
                waitingForResponse.set(false);
                Logger.printException(() -> "Could not request YTM playlist: " +
                        playlistBrowseId, ex);
            }
        }

        /**
         * {@link SupportAndroidAutoPatch#findFirstPlayableSong} checks for songs; an empty playlist can still have a Play button.
         * Liked Music has no Play button, so it uses the first song's command.
         * Pass playback to {@link #postToPlaybackThread}.
         */
        private void readResponse(
                ListenableFuture<PhoneBrowseResponse> future, Handler callbackHandler,
                Bundle playbackExtras) {
            try {
                PhoneBrowseResponse response = future.get();
                PhoneBrowseItem firstPlayableSong = findFirstPlayableSong(response);
                if (firstPlayableSong == null) {
                    Logger.printDebug(() ->
                            "Selected Android Auto playlist has no playable songs");
                    return;
                }
                String ytmPlaybackMediaId = LIKED_MUSIC_BROWSE_ID.equals(playlistBrowseId)
                        ? firstPlayableSong.patch_getCommandMediaId()
                        : response.patch_getPlaylistPlayButtonMediaId();
                if (ytmPlaybackMediaId == null) {
                    Logger.printDebug(() -> "Selected Android Auto playlist has no playback command");
                    return;
                }
                // onPlayFromMediaId first calls our handlePlayFromMediaId again.
                // This YTM media ID makes handlePlayFromMediaId return false, so YTM's playback code runs.
                postToPlaybackThread(callbackHandler, () ->
                        callback.onPlayFromMediaId(ytmPlaybackMediaId, playbackExtras));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                Logger.printException(() -> "YTM playlist request interrupted: " +
                        playlistBrowseId, ex);
            } catch (ExecutionException | RuntimeException ex) {
                Logger.printException(() -> "YTM playlist request failed: " +
                        playlistBrowseId, ex);
            }
        }

        /** Runs on YTM's playback thread only if the selection and original {@link PhoneBrowseClient} are unchanged. */
        private void postToPlaybackThread(Handler callbackHandler, Runnable task) {
            callbackHandler.post(() -> {
                if (requestGeneration != playRequestGeneration.get()) return;
                if (browseClientAtStart != phoneBrowseClient) return;
                try {
                    task.run();
                } catch (Exception ex) {
                    Logger.printException(() -> "Could not play Android Auto playlist: " +
                            playlistBrowseId, ex);
                }
            });
        }
    }

    // Check playlist contents

    private static PhoneBrowseItem findFirstPlayableSong(PhoneBrowseResponse playlistResponse) {
        for (PhoneBrowseTab tab : playlistResponse.patch_getTabs()) {
            SectionList sectionList = tab.patch_getSectionList();
            if (sectionList == null) continue;
            for (Object sectionContent : sectionList.patch_getContents()) {
                if (!(sectionContent instanceof PlaylistContents)) continue;
                for (PhoneBrowseItem playlistItem :
                        ((PlaylistContents) sectionContent).patch_getItems()) {
                    // The "Add a song" button has a command but no song ID; exclude it from the song check.
                    if (!playlistItem.patch_hasPlayableVideoId()) continue;
                    if (playlistItem.patch_getCommandMediaId() != null) return playlistItem;
                }
            }
        }
        return null;
    }
}
