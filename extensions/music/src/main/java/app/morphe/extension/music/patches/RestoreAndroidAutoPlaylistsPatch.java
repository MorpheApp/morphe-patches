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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;

/**
 * Loads YTM's phone Library when Android Auto requests Playlists.
 *
 * <p>FEmusic_library_landing returns playlist titles and Browse IDs in GridRenderer. Playlist pages
 * contain the playback commands used to create Android Auto media IDs.
 */
@SuppressWarnings("unused")
public final class RestoreAndroidAutoPlaylistsPatch {
    private static final String LIBRARY_BROWSE_ID = "FEmusic_library_landing";
    private static final String LIKED_MUSIC_BROWSE_ID = "VLLM";
    private static final String EPISODES_FOR_LATER_BROWSE_ID = "VLSE";
    private static final String PLAYLISTS_TITLE_RESOURCE = "library_playlists_shelf_title";
    private static final Executor REQUEST_EXECUTOR = Utils::runOnBackgroundThread;
    private static final Set<String> PLAYLIST_CATEGORY_IDS =
            ConcurrentHashMap.newKeySet();

    public interface BrowseService {
        @NonNull ListenableFuture<?> patch_requestBrowse(
                @NonNull String browseId, @NonNull Executor executor);
        @NonNull ListenableFuture<?> patch_requestContinuation(
                @NonNull Object continuation, @NonNull Executor executor);
    }

    public interface BrowseResponse {
        @NonNull Iterable<?> patch_getTabs();
        @Nullable Object patch_getContinuationGrid();
        @Nullable String patch_getPlaylistMediaId();
    }

    public interface BrowseTab {
        @Nullable Object patch_getSectionList();
    }

    public interface SectionList {
        @NonNull Iterable<?>[] patch_getItemLists();
    }

    public interface PlaylistGrid {
        @Nullable Iterable<?> patch_getItems();
        @Nullable Iterable<?> patch_getContinuations();
    }

    public interface PlaylistShelf {
        @Nullable Iterable<?> patch_getItems();
    }

    public interface LoadChildrenResult {
        @Nullable String patch_getParentMediaId();
        void patch_sendResult(@NonNull List<MediaBrowserCompat.MediaItem> items);
    }

    public interface MusicItem {
        @Nullable String patch_getBrowseId();
        @Nullable String patch_getMediaId();
        @Nullable Uri patch_getArtworkUri();
        @Nullable CharSequence patch_getTitle();
        @Nullable CharSequence patch_getSubtitle();
    }

    @Nullable
    private static volatile BrowseService browseService;

    private RestoreAndroidAutoPlaylistsPatch() {
    }

    /** Injection point. Called from MusicBrowserService's generated superclass during onCreate. */
    public static void setBrowseService(@Nullable BrowseService service) {
        if (service == null) return;
        if (browseService != service) PLAYLIST_CATEGORY_IDS.clear();
        browseService = service;
        Logger.printDebug(() -> "BrowseService ready: " +
                service.getClass().getName());
    }

    /** Injection point. A true result skips YTM's loadChildren response. */
    public static boolean replacePlaylists(@NonNull Object result) {
        try {
            if (browseService == null || !(result instanceof LoadChildrenResult)) {
                return false;
            }
            LoadChildrenResult loadChildrenResult = (LoadChildrenResult) result;
            if (!isPlaylistRequest(loadChildrenResult)) return false;
            loadPlaylists(loadChildrenResult);
            return true;
        } catch (RuntimeException ex) {
            Logger.printException(() -> "Could not start Android Auto playlist request", ex);
            return false;
        }
    }

    /** Injection point. Saves the Playlists parent ID used by later loadChildren requests. */
    public static void rememberPlaylistCategoryId(
            @Nullable String mediaId, @Nullable CharSequence title) {
        if (title == null || !ResourceUtils.getString(PLAYLISTS_TITLE_RESOURCE)
                .contentEquals(title)) return;
        if (mediaId != null) PLAYLIST_CATEGORY_IDS.add(mediaId);
    }

    private static void loadPlaylists(LoadChildrenResult result) {
        LibraryState state = new LibraryState();
        loadLibraryPage(result, state,
                browseService.patch_requestBrowse(LIBRARY_BROWSE_ID, REQUEST_EXECUTOR), false);
    }

    private static void loadLibraryPage(
            LoadChildrenResult result, LibraryState state, ListenableFuture<?> request,
            boolean isContinuation) {
        request.addListener(() -> {
            try {
                BrowseResponse response = (BrowseResponse) request.get();
                Object continuation = collectPage(response, state, isContinuation);
                if (continuation != null) {
                    loadLibraryPage(result, state,
                            browseService.patch_requestContinuation(
                                    continuation, REQUEST_EXECUTOR), true);
                    return;
                }
                loadPlaylistMediaItems(result, state.playlists);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                Logger.printException(() -> "Library Browse request interrupted", ex);
                sendResult(result, Collections.emptyList());
            } catch (ExecutionException | RuntimeException ex) {
                Logger.printException(() -> "Library Browse request failed", ex);
                sendResult(result, Collections.emptyList());
            }
        }, REQUEST_EXECUTOR);
    }

    private static void sendResult(
            LoadChildrenResult result, List<MediaBrowserCompat.MediaItem> items) {
        try {
            result.patch_sendResult(items);
        } catch (RuntimeException ex) {
            Logger.printException(() -> "Could not deliver Android Auto playlists", ex);
        }
    }

    private static Object collectPage(
            BrowseResponse response, LibraryState state, boolean isContinuation) {
        Object continuation = null;
        if (isContinuation) {
            Object grid = response.patch_getContinuationGrid();
            if (grid != null) {
                continuation = collectPlaylists(
                        Collections.singletonList(grid), state, null);
            }
        } else {
            for (Object tab : response.patch_getTabs()) {
                SectionList sectionList = (SectionList)
                        ((BrowseTab) tab).patch_getSectionList();
                if (sectionList == null) continue;
                for (Iterable<?> sectionItems : sectionList.patch_getItemLists()) {
                    continuation = collectPlaylists(sectionItems, state, continuation);
                }
            }
        }
        Logger.printDebug(() -> "Found Library playlists: " + state.playlists.size());
        return continuation;
    }

    private static Object collectPlaylists(
            Iterable<?> sectionItems, LibraryState state, Object continuation) {
        for (Object sectionItem : sectionItems) {
            if (!(sectionItem instanceof PlaylistGrid)) continue;
            PlaylistGrid grid = (PlaylistGrid) sectionItem;
            if (continuation == null) {
                Iterable<?> continuations = grid.patch_getContinuations();
                if (continuations != null) {
                    Iterator<?> iterator = continuations.iterator();
                    if (iterator.hasNext()) continuation = iterator.next();
                }
            }

            Iterable<?> playlistItems = grid.patch_getItems();
            if (playlistItems == null) continue;
            for (Object item : playlistItems) {
                if (!(item instanceof MusicItem)) continue;
                try {
                    addPlaylist((MusicItem) item, state);
                } catch (RuntimeException ex) {
                    Logger.printException(() -> "Library playlist skipped", ex);
                }
            }
        }
        return continuation;
    }

    private static void addPlaylist(MusicItem item, LibraryState state) {
        String browseId = item.patch_getBrowseId();
        if (browseId == null || state.seenBrowseIds.contains(browseId)) return;
        // Episodes for Later (VLSE) has no playlist Play command.
        if (EPISODES_FOR_LATER_BROWSE_ID.equals(browseId)) return;

        CharSequence titleText = item.patch_getTitle();
        String title = titleText == null ? "" : titleText.toString();
        if (title.isEmpty()) return;
        state.seenBrowseIds.add(browseId);
        // Subtitle and artwork failures do not block playback.
        state.playlists.add(new LibraryPlaylist(
                browseId,
                title,
                subtitleOrEmpty(item),
                artworkUriOrNull(item)));
    }

    private static void loadPlaylistMediaItems(
            LoadChildrenResult result, List<LibraryPlaylist> playlists) {
        if (playlists.isEmpty()) {
            sendResult(result, Collections.emptyList());
            return;
        }

        // The array preserves Library order when requests finish out of order.
        MediaBrowserCompat.MediaItem[] items = new MediaBrowserCompat.MediaItem[playlists.size()];
        AtomicInteger remaining = new AtomicInteger(playlists.size());
        for (int index = 0; index < playlists.size(); index++) {
            int itemIndex = index;
            LibraryPlaylist playlist = playlists.get(index);
            ListenableFuture<?> request = browseService.patch_requestBrowse(
                    playlist.browseId, REQUEST_EXECUTOR);
            request.addListener(() -> {
                try {
                    BrowseResponse response = (BrowseResponse) request.get();
                    // Liked Music (VLLM) has no ButtonRenderer Play command in response.q.
                    String mediaId = LIKED_MUSIC_BROWSE_ID.equals(playlist.browseId)
                            ? firstTrackMediaId(response)
                            : response.patch_getPlaylistMediaId();
                    if (mediaId != null && !mediaId.isEmpty()) {
                        items[itemIndex] = createMediaItem(
                                mediaId, playlist.title, playlist.subtitle, playlist.artwork);
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    Logger.printException(() -> "Playlist Browse request interrupted", ex);
                } catch (ExecutionException | RuntimeException ex) {
                    Logger.printException(() -> "Playlist Browse request failed", ex);
                } finally {
                    completePlaylistRequest(result, items, remaining);
                }
            }, REQUEST_EXECUTOR);
        }
    }

    private static void completePlaylistRequest(
            LoadChildrenResult result, MediaBrowserCompat.MediaItem[] items,
            AtomicInteger remaining) {
        if (remaining.decrementAndGet() != 0) return;
        List<MediaBrowserCompat.MediaItem> mediaItems = new ArrayList<>(items.length);
        for (MediaBrowserCompat.MediaItem item : items) {
            if (item != null) mediaItems.add(item);
        }
        sendResult(result, mediaItems);
    }

    private static String firstTrackMediaId(BrowseResponse response) {
        for (Object tab : response.patch_getTabs()) {
            SectionList sectionList = (SectionList)
                    ((BrowseTab) tab).patch_getSectionList();
            if (sectionList == null) continue;
            for (Iterable<?> sectionItems : sectionList.patch_getItemLists()) {
                for (Object shelf : sectionItems) {
                    if (!(shelf instanceof PlaylistShelf)) continue;
                    Iterable<?> playlistTracks = ((PlaylistShelf) shelf).patch_getItems();
                    if (playlistTracks == null) continue;
                    for (Object track : playlistTracks) {
                        if (!(track instanceof MusicItem)) continue;
                        String mediaId = ((MusicItem) track).patch_getMediaId();
                        if (mediaId != null && !mediaId.isEmpty()) return mediaId;
                    }
                }
            }
        }
        return null;
    }

    private static String subtitleOrEmpty(MusicItem item) {
        try {
            CharSequence subtitle = item.patch_getSubtitle();
            return subtitle == null ? "" : subtitle.toString();
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static Uri artworkUriOrNull(MusicItem item) {
        try {
            return item.patch_getArtworkUri();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static MediaBrowserCompat.MediaItem createMediaItem(
            String mediaId, String title, String subtitle, Uri iconUri) {
        MediaDescriptionCompat description = new MediaDescriptionCompat(
                mediaId, title, subtitle, null, null, iconUri, null, null);
        return new MediaBrowserCompat.MediaItem(
                description, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE);
    }

    private static boolean isPlaylistRequest(LoadChildrenResult result) {
        String parentMediaId = result.patch_getParentMediaId();
        return parentMediaId != null && PLAYLIST_CATEGORY_IDS.contains(parentMediaId);
    }

    private static final class LibraryState {
        private final List<LibraryPlaylist> playlists = new ArrayList<>();
        private final Set<String> seenBrowseIds = new HashSet<>();
    }

    private static final class LibraryPlaylist {
        private final String browseId;
        private final String title;
        private final String subtitle;
        private final Uri artwork;

        private LibraryPlaylist(
                String browseId, String title, String subtitle, Uri artwork) {
            this.browseId = browseId;
            this.title = title;
            this.subtitle = subtitle;
            this.artwork = artwork;
        }
    }
}
