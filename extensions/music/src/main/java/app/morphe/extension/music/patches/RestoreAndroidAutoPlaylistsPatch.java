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
 * <p>FEmusic_library_landing returns playlist names and IDs. A normal playlist page's Play
 * button supplies the action serialized as Android Auto's media ID.
 */
@SuppressWarnings("unused")
public final class RestoreAndroidAutoPlaylistsPatch {
    private static final String LIBRARY_PLAYLISTS_ID = "FEmusic_library_landing";
    private static final String LIKED_MUSIC_PLAYLIST_ID = "VLLM";
    private static final String EPISODES_FOR_LATER_PLAYLIST_ID = "VLSE";
    private static final String PLAYLISTS_TITLE_RESOURCE = "library_playlists_shelf_title";
    private static final Executor BACKGROUND_EXECUTOR = Utils::runOnBackgroundThread;
    private static final Set<String> PLAYLIST_CATEGORY_IDS =
            ConcurrentHashMap.newKeySet();

    public interface PlaylistLoader {
        @NonNull ListenableFuture<?> patch_requestPage(
                @NonNull String pageId, @NonNull Executor executor);
        @NonNull ListenableFuture<?> patch_requestMorePlaylists(
                @NonNull Object loadMoreAction, @NonNull Executor executor);
    }

    public interface PlaylistResponse {
        @NonNull Iterable<?> patch_getPages();
        @Nullable Object patch_getMorePlaylists();
        @Nullable String patch_getPlaybackId();
    }

    public interface PlaylistPage {
        @Nullable Object patch_getBody();
    }

    public interface PlaylistBody {
        @NonNull Iterable<?>[] patch_getGroups();
    }

    public interface PlaylistListing {
        @Nullable Iterable<?> patch_getPlaylists();
        @Nullable Iterable<?> patch_getLoadMoreActions();
    }

    public interface PlaylistTracks {
        @Nullable Iterable<?> patch_getTracks();
    }

    public interface AndroidAutoResult {
        @Nullable String patch_getParentMediaId();
        void patch_sendResult(@NonNull List<MediaBrowserCompat.MediaItem> playlists);
    }

    public interface PlaylistOrTrack {
        @Nullable String patch_getPlaylistId();
        @Nullable String patch_getPlaybackId();
        @Nullable Uri patch_getArtworkUri();
        @Nullable CharSequence patch_getTitle();
        @Nullable CharSequence patch_getSubtitle();
    }

    @Nullable
    private static volatile PlaylistLoader playlistLoader;

    private RestoreAndroidAutoPlaylistsPatch() {
    }

    /**
     * Injection point. Captures YTM's BS_GET_BROWSE_DATA object during MusicBrowserService.onCreate.
     */
    public static void setPlaylistLoader(@Nullable PlaylistLoader loader) {
        if (loader == null) return;
        if (playlistLoader != loader) PLAYLIST_CATEGORY_IDS.clear();
        playlistLoader = loader;
        Logger.printDebug(() -> "Playlist loader ready: " +
                loader.getClass().getName());
    }

    /** Injection point. Returning true skips YTM's loadChildren response. */
    public static boolean handlePlaylistLoad(@NonNull Object value) {
        try {
            if (playlistLoader == null || !(value instanceof AndroidAutoResult)) {
                return false;
            }
            AndroidAutoResult result = (AndroidAutoResult) value;
            if (!isPlaylistCategoryRequest(result)) return false;
            loadPlaylists(result);
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

    private static void loadPlaylists(AndroidAutoResult result) {
        PlaylistState state = new PlaylistState();
        loadPlaylistResponse(result, state,
                playlistLoader.patch_requestPage(
                        LIBRARY_PLAYLISTS_ID, BACKGROUND_EXECUTOR), false);
    }

    private static void loadPlaylistResponse(
            AndroidAutoResult result, PlaylistState state,
            ListenableFuture<?> responseFuture,
            boolean isMorePlaylistsResponse) {
        responseFuture.addListener(() -> {
            try {
                PlaylistResponse response = (PlaylistResponse) responseFuture.get();
                Object loadMoreAction = collectPlaylistsFromResponse(
                        response, state, isMorePlaylistsResponse);
                if (loadMoreAction != null) {
                    loadPlaylistResponse(result, state,
                            playlistLoader.patch_requestMorePlaylists(
                                    loadMoreAction, BACKGROUND_EXECUTOR), true);
                    return;
                }
                loadPlayablePlaylists(result, state.playlists);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                Logger.printException(() -> "Playlists request interrupted", ex);
                deliverPlaylists(result, Collections.emptyList());
            } catch (ExecutionException | RuntimeException ex) {
                Logger.printException(() -> "Playlists request failed", ex);
                deliverPlaylists(result, Collections.emptyList());
            }
        }, BACKGROUND_EXECUTOR);
    }

    private static void deliverPlaylists(
            AndroidAutoResult result, List<MediaBrowserCompat.MediaItem> playlists) {
        try {
            result.patch_sendResult(playlists);
        } catch (RuntimeException ex) {
            Logger.printException(() -> "Could not deliver Android Auto playlists", ex);
        }
    }

    private static Object collectPlaylistsFromResponse(
            PlaylistResponse response, PlaylistState state,
            boolean isMorePlaylistsResponse) {
        Object loadMoreAction = null;
        if (isMorePlaylistsResponse) {
            Object morePlaylists = response.patch_getMorePlaylists();
            if (morePlaylists != null) {
                loadMoreAction = collectPlaylists(
                        Collections.singletonList(morePlaylists), state, null);
            }
        } else {
            for (Object page : response.patch_getPages()) {
                PlaylistBody body = (PlaylistBody) ((PlaylistPage) page).patch_getBody();
                if (body == null) continue;
                for (Iterable<?> group : body.patch_getGroups()) {
                    loadMoreAction = collectPlaylists(group, state, loadMoreAction);
                }
            }
        }
        Logger.printDebug(() -> "Found playlists: " + state.playlists.size());
        return loadMoreAction;
    }

    private static Object collectPlaylists(
            Iterable<?> group, PlaylistState state, Object loadMoreAction) {
        for (Object value : group) {
            if (!(value instanceof PlaylistListing)) continue;
            PlaylistListing listing = (PlaylistListing) value;
            if (loadMoreAction == null) {
                Iterable<?> loadMoreActions = listing.patch_getLoadMoreActions();
                if (loadMoreActions != null) {
                    Iterator<?> iterator = loadMoreActions.iterator();
                    if (iterator.hasNext()) loadMoreAction = iterator.next();
                }
            }

            Iterable<?> playlists = listing.patch_getPlaylists();
            if (playlists == null) continue;
            for (Object playlist : playlists) {
                if (!(playlist instanceof PlaylistOrTrack)) continue;
                try {
                    addPlaylist((PlaylistOrTrack) playlist, state);
                } catch (RuntimeException ex) {
                    Logger.printException(() -> "Playlist skipped", ex);
                }
            }
        }
        return loadMoreAction;
    }

    private static void addPlaylist(PlaylistOrTrack playlist, PlaylistState state) {
        String playlistId = playlist.patch_getPlaylistId();
        if (playlistId == null || state.seenPlaylistIds.contains(playlistId)) return;
        // Episodes for Later (VLSE) has no Play button.
        if (EPISODES_FOR_LATER_PLAYLIST_ID.equals(playlistId)) return;

        CharSequence titleText = playlist.patch_getTitle();
        String title = titleText == null ? "" : titleText.toString();
        if (title.isEmpty()) return;
        state.seenPlaylistIds.add(playlistId);
        // Subtitle and artwork failures do not block playback.
        state.playlists.add(new Playlist(
                playlistId,
                title,
                subtitleOrEmpty(playlist),
                artworkUriOrNull(playlist)));
    }

    private static void loadPlayablePlaylists(
            AndroidAutoResult result, List<Playlist> playlists) {
        if (playlists.isEmpty()) {
            deliverPlaylists(result, Collections.emptyList());
            return;
        }

        // The array preserves playlist order when requests finish out of order.
        MediaBrowserCompat.MediaItem[] androidAutoPlaylists =
                new MediaBrowserCompat.MediaItem[playlists.size()];
        AtomicInteger remaining = new AtomicInteger(playlists.size());
        for (int index = 0; index < playlists.size(); index++) {
            int playlistIndex = index;
            Playlist playlist = playlists.get(index);
            ListenableFuture<?> responseFuture = playlistLoader.patch_requestPage(
                    playlist.playlistId, BACKGROUND_EXECUTOR);
            responseFuture.addListener(() -> {
                try {
                    PlaylistResponse response = (PlaylistResponse) responseFuture.get();
                    // Liked Music (VLLM) has no Play button in response.q; use its first track.
                    String playbackId = LIKED_MUSIC_PLAYLIST_ID.equals(playlist.playlistId)
                            ? firstTrackPlaybackId(response)
                            : response.patch_getPlaybackId();
                    if (playbackId != null && !playbackId.isEmpty()) {
                        androidAutoPlaylists[playlistIndex] = createAndroidAutoPlaylist(
                                playbackId, playlist.title, playlist.subtitle, playlist.artworkUri);
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    Logger.printException(() -> "Playlist playback request interrupted", ex);
                } catch (ExecutionException | RuntimeException ex) {
                    Logger.printException(() -> "Playlist playback request failed", ex);
                } finally {
                    finishPlaylistLoading(result, androidAutoPlaylists, remaining);
                }
            }, BACKGROUND_EXECUTOR);
        }
    }

    private static void finishPlaylistLoading(
            AndroidAutoResult result, MediaBrowserCompat.MediaItem[] androidAutoPlaylists,
            AtomicInteger remaining) {
        if (remaining.decrementAndGet() != 0) return;
        List<MediaBrowserCompat.MediaItem> playlists =
                new ArrayList<>(androidAutoPlaylists.length);
        for (MediaBrowserCompat.MediaItem playlist : androidAutoPlaylists) {
            if (playlist != null) playlists.add(playlist);
        }
        deliverPlaylists(result, playlists);
    }

    private static String firstTrackPlaybackId(PlaylistResponse response) {
        for (Object page : response.patch_getPages()) {
            PlaylistBody body = (PlaylistBody) ((PlaylistPage) page).patch_getBody();
            if (body == null) continue;
            for (Iterable<?> group : body.patch_getGroups()) {
                for (Object value : group) {
                    if (!(value instanceof PlaylistTracks)) continue;
                    Iterable<?> tracks = ((PlaylistTracks) value).patch_getTracks();
                    if (tracks == null) continue;
                    for (Object track : tracks) {
                        if (!(track instanceof PlaylistOrTrack)) continue;
                        String playbackId = ((PlaylistOrTrack) track).patch_getPlaybackId();
                        if (playbackId != null && !playbackId.isEmpty()) return playbackId;
                    }
                }
            }
        }
        return null;
    }

    private static String subtitleOrEmpty(PlaylistOrTrack playlist) {
        try {
            CharSequence subtitle = playlist.patch_getSubtitle();
            return subtitle == null ? "" : subtitle.toString();
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static Uri artworkUriOrNull(PlaylistOrTrack playlist) {
        try {
            return playlist.patch_getArtworkUri();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static MediaBrowserCompat.MediaItem createAndroidAutoPlaylist(
            String mediaId, String title, String subtitle, Uri artworkUri) {
        MediaDescriptionCompat description = new MediaDescriptionCompat(
                mediaId, title, subtitle, null, null, artworkUri, null, null);
        return new MediaBrowserCompat.MediaItem(
                description, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE);
    }

    private static boolean isPlaylistCategoryRequest(AndroidAutoResult result) {
        String parentMediaId = result.patch_getParentMediaId();
        return parentMediaId != null && PLAYLIST_CATEGORY_IDS.contains(parentMediaId);
    }

    private static final class PlaylistState {
        private final List<Playlist> playlists = new ArrayList<>();
        private final Set<String> seenPlaylistIds = new HashSet<>();
    }

    private static final class Playlist {
        private final String playlistId;
        private final String title;
        private final String subtitle;
        private final Uri artworkUri;

        private Playlist(
                String playlistId, String title, String subtitle, Uri artworkUri) {
            this.playlistId = playlistId;
            this.title = title;
            this.subtitle = subtitle;
            this.artworkUri = artworkUri;
        }
    }
}
