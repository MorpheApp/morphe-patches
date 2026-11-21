/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2556
 * https://github.com/MorpheApp/morphe-patches/pull/5141
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.album;

import android.util.Base64;

import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.patches.BaseVideoInformation;
import app.morphe.extension.shared.patches.OfflineVideoExporter;

/**
 * Plays the songs of an album instead of the music videos the album queue holds for them.
 *
 * <p>Every music video of an album queue carries the video id of the album song it stands for.
 * The queue is changed before the app builds it from the watch next response, so the songs are
 * real queue entries, with their own title, artwork and length, and saving to a playlist saves
 * the song. A tapped album track is changed the same way before the app starts playing it.
 */
@SuppressWarnings("unused")
public final class PlayAlbumSongsPatch {

    /**
     * YouTube Music album playlist ids share a stable "OLAK" prefix.
     */
    public static final String YOUTUBE_MUSIC_ALBUM_PREFIX = "OLAK";

    /**
     * How long the watch next response waits for the songs of an album it holds. Later responses
     * of the same album use the songs at once.
     */
    private static final long MAX_MILLISECONDS_TO_WAIT_FOR_SONGS = 2000;

    /**
     * Field numbers of the WatchNextResponse, down to the PlaylistPanelRenderer of the queue.
     */
    private static final int WATCH_NEXT_CONTENTS_FIELD = 7;
    private static final int SINGLE_COLUMN_MUSIC_WATCH_NEXT_RESULTS_RENDERER_FIELD = 125354411;
    private static final int TABBED_RENDERER_FIELD = 7;
    private static final int WATCH_NEXT_TABBED_RESULTS_RENDERER_FIELD = 91700600;
    private static final int TABS_FIELD = 1;
    private static final int TAB_RENDERER_FIELD = 58174010;
    private static final int TAB_CONTENT_FIELD = 4;
    private static final int MUSIC_QUEUE_RENDERER_FIELD = 278751218;
    private static final int MUSIC_QUEUE_CONTENT_FIELD = 1;
    private static final int PLAYLIST_PANEL_RENDERER_FIELD = 50631000;

    private static final int[] PLAYLIST_PANEL_PATH = {
            WATCH_NEXT_CONTENTS_FIELD,
            SINGLE_COLUMN_MUSIC_WATCH_NEXT_RESULTS_RENDERER_FIELD,
            TABBED_RENDERER_FIELD,
            WATCH_NEXT_TABBED_RESULTS_RENDERER_FIELD,
            TABS_FIELD,
            TAB_RENDERER_FIELD,
            TAB_CONTENT_FIELD,
            MUSIC_QUEUE_RENDERER_FIELD,
            MUSIC_QUEUE_CONTENT_FIELD,
            PLAYLIST_PANEL_RENDERER_FIELD
    };

    /**
     * WatchNextResponse.currentVideoEndpoint, the navigation endpoint of the video the response
     * is for.
     */
    private static final int CURRENT_VIDEO_ENDPOINT_FIELD = 9;

    /**
     * Field numbers of the PlaylistPanelRenderer and its entries.
     */
    private static final int PLAYLIST_PANEL_CONTENTS_FIELD = 2;
    private static final int PLAYLIST_PANEL_PLAYLIST_ID_FIELD = 5;
    private static final int PLAYLIST_PANEL_VIDEO_RENDERER_FIELD = 51779701;

    /**
     * Field numbers of the PlaylistPanelVideoRenderer that belong to the place of an entry in the
     * queue, rather than to its video.
     */
    private static final int RENDERER_SELECTED_FIELD = 6;
    private static final int RENDERER_NAVIGATION_ENDPOINT_FIELD = 7;
    private static final int RENDERER_PLAYLIST_SET_VIDEO_ID_FIELD = 26;

    /**
     * NavigationEndpoint.watchEndpoint extension.
     */
    private static final int WATCH_ENDPOINT_FIELD = 48687757;

    /**
     * NavigationEndpoint.browseEndpoint extension, and its browse id. Album pages have browse ids
     * with a stable "MPREb_" prefix.
     */
    private static final int BROWSE_ENDPOINT_FIELD = 48687626;
    private static final int BROWSE_ENDPOINT_BROWSE_ID_FIELD = 2;
    private static final String ALBUM_BROWSE_ID_PREFIX = "MPREb_";

    /**
     * Field numbers of the WatchEndpoint.
     */
    private static final int WATCH_ENDPOINT_VIDEO_ID_FIELD = 1;
    private static final int WATCH_ENDPOINT_PLAYLIST_ID_FIELD = 2;
    private static final int WATCH_ENDPOINT_INDEX_FIELD = 3;
    private static final int WATCH_ENDPOINT_PARAMS_FIELD = 5;
    private static final int WATCH_ENDPOINT_PLAYER_PARAMS_FIELD = 9;
    private static final int WATCH_ENDPOINT_PLAYLIST_SET_VIDEO_ID_FIELD = 13;
    private static final int WATCH_ENDPOINT_LOGGING_CONTEXT_FIELD = 26;
    /**
     * WatchEndpoint.watchEndpointSupportedOnesieConfig, which prefetches the streams of its video.
     */
    private static final int WATCH_ENDPOINT_ONESIE_CONFIG_FIELD = 79857908;
    private static final int WATCH_ENDPOINT_MUSIC_SUPPORTED_CONFIGS_FIELD = 136656028;

    /**
     * The fields of a watch endpoint that tell where in the album it plays.
     */
    private static final int[] WATCH_ENDPOINT_ALBUM_FIELDS = {
            WATCH_ENDPOINT_PLAYLIST_ID_FIELD,
            WATCH_ENDPOINT_INDEX_FIELD,
            WATCH_ENDPOINT_PLAYER_PARAMS_FIELD,
            WATCH_ENDPOINT_PLAYLIST_SET_VIDEO_ID_FIELD,
            WATCH_ENDPOINT_LOGGING_CONTEXT_FIELD
    };

    /**
     * WatchEndpointMusicSupportedConfigs.watchEndpointMusicConfig, and its musicVideoType with
     * the MusicVideoType values it uses.
     */
    private static final int MUSIC_SUPPORTED_CONFIGS_MUSIC_CONFIG_FIELD = 136657325;
    private static final int MUSIC_CONFIG_VIDEO_TYPE_FIELD = 2;
    private static final int MUSIC_VIDEO_TYPE_ATV = 1;
    private static final int MUSIC_VIDEO_TYPE_OMV = 2;

    /**
     * Field of the watch endpoint params that holds the video id of the album song of a music video.
     */
    private static final int PARAMS_SONG_VIDEO_ID_FIELD = 52;

    private static final String URL_ENCODED_PADDING = "%3D";
    private static final String BASE64_PADDING = "=";

    private static final int NUMBER_OF_ALBUM_TRACKS_TO_TRACK = 500;

    private static final long NO_INDEX = -1;

    /**
     * An album track, identified the same way its watch endpoints identify it.
     */
    private record AlbumTrack(String playlistId, long index) {
    }

    /**
     * The music video an album queue holds for a track, and the song of the track.
     */
    private record TrackSong(String videoId, String songVideoId) {
    }

    /**
     * Album tracks seen in album queues, so a tapped track can be changed before it plays.
     */
    private static final Map<AlbumTrack, TrackSong> trackSongs = Collections.synchronizedMap(
            Utils.createSizeRestrictedMap(NUMBER_OF_ALBUM_TRACKS_TO_TRACK));

    private PlayAlbumSongsPatch() {
    }

    /**
     * Injection point.
     *
     * @param response WatchNextResponse the app is about to build its watch next model from.
     * @return The response to build the model from instead, or the original response.
     */
    public static Object rewriteWatchNextResponse(Object response) {
        try {
            if (!Settings.PLAY_ALBUMS_SONGS.get()) return response;

            byte[] rewritten = rewriteResponse(serializeMessage(response));
            return rewritten == null
                    ? response
                    : parseMessage(watchNextResponseDefaultInstance(), rewritten, extensionRegistry());
        } catch (Exception ex) {
            Logger.printException(() -> "rewriteWatchNextResponse failure", ex);
            return response;
        }
    }

    /**
     * Injection point.
     *
     * @param command Command about to be resolved.
     * @return The command to resolve instead, or the original command.
     */
    public static Object overrideCommand(@Nullable Object command) {
        try {
            if (command == null || !Settings.PLAY_ALBUMS_SONGS.get()) return command;

            byte[] serializedCommand = serializeMessage(command);
            fetchAlbumOf(serializedCommand);

            byte[] rewritten = AlbumProto.rewritePath(serializedCommand,
                    new int[]{WATCH_ENDPOINT_FIELD}, 0, PlayAlbumSongsPatch::rewriteTappedTrack);
            return rewritten == null
                    ? command
                    : parseMessage(commandDefaultInstance(), rewritten, extensionRegistry());
        } catch (Exception ex) {
            Logger.printException(() -> "overrideCommand failure", ex);
            return command;
        }
    }

    /**
     * An album page and a tapped album track start fetching the album, so the songs are ready
     * before the watch next response of the album, which the app builds on the main thread.
     */
    private static void fetchAlbumOf(byte[] command) {
        String browseId = AlbumProto.stringField(
                AlbumProto.bytesField(command, BROWSE_ENDPOINT_FIELD), BROWSE_ENDPOINT_BROWSE_ID_FIELD);
        if (browseId.startsWith(ALBUM_BROWSE_ID_PREFIX)) {
            AlbumRequests.fetchAlbumPage(browseId);
            return;
        }

        String playlistId = AlbumProto.stringField(
                AlbumProto.bytesField(command, WATCH_ENDPOINT_FIELD), WATCH_ENDPOINT_PLAYLIST_ID_FIELD);
        if (playlistId.startsWith(YOUTUBE_MUSIC_ALBUM_PREFIX)) {
            AlbumRequests.fetchAlbum(playlistId);
        }
    }

    /**
     * Remembers the song of each music video of an album queue, from a watch next response.
     *
     * @return The video ids of the songs of the music videos.
     */
    static List<String> rememberAlbumQueue(byte[] response) {
        List<String> songVideoIds = new ArrayList<>();
        AlbumProto.rewritePath(response, PLAYLIST_PANEL_PATH, 0, panel -> {
            String playlistId = AlbumProto.stringField(panel, PLAYLIST_PANEL_PLAYLIST_ID_FIELD);
            if (playlistId.startsWith(YOUTUBE_MUSIC_ALBUM_PREFIX)) {
                songVideoIds.addAll(rememberTrackSongs(playlistId, panel, AlbumProto.parse(panel)));
            }
            // Only read.
            return null;
        });
        return songVideoIds;
    }

    @Nullable
    private static byte[] rewriteResponse(byte[] response) {
        // The response names the track that is about to play, by its entry in the album queue.
        String currentEntryId = AlbumProto.stringField(AlbumProto.bytesField(AlbumProto.bytesField(
                        response, CURRENT_VIDEO_ENDPOINT_FIELD), WATCH_ENDPOINT_FIELD),
                WATCH_ENDPOINT_PLAYLIST_SET_VIDEO_ID_FIELD);
        long[] currentIndex = {NO_INDEX};

        byte[] withSongs = AlbumProto.rewritePath(response, PLAYLIST_PANEL_PATH, 0,
                panel -> rewritePlaylistPanel(panel, currentEntryId, currentIndex));
        if (withSongs == null) return null;

        byte[] withCurrentSong = AlbumProto.rewritePath(withSongs,
                new int[]{CURRENT_VIDEO_ENDPOINT_FIELD, WATCH_ENDPOINT_FIELD}, 0,
                watchEndpoint -> rewriteCurrentTrack(watchEndpoint, currentIndex[0]));
        return withCurrentSong == null ? withSongs : withCurrentSong;
    }

    /**
     * The current track is the music video when the app opened the album at a music video, and it
     * is at the first entry when the app opened the album at a song, since the album queue only
     * holds the music video of that track.
     */
    @Nullable
    private static byte[] rewriteCurrentTrack(byte[] watchEndpoint, long index) {
        byte[] song = rewriteKnownTrack(watchEndpoint);
        byte[] rewritten = song == null ? watchEndpoint : song;
        if (index != NO_INDEX && AlbumProto.varintField(rewritten, WATCH_ENDPOINT_INDEX_FIELD) != index) {
            rewritten = AlbumProto.withVarintField(rewritten, WATCH_ENDPOINT_INDEX_FIELD, index);
        }
        return rewritten == watchEndpoint ? null : rewritten;
    }

    /**
     * Replaces the music videos of an album queue with the songs they stand for, and selects the
     * entry of the current track.
     *
     * @param currentEntryId Entry of the album queue the current track plays at, or empty.
     * @param currentIndex   Set to the album position of that entry.
     */
    @Nullable
    private static byte[] rewritePlaylistPanel(byte[] panel, String currentEntryId, long[] currentIndex) {
        String playlistId = AlbumProto.stringField(panel, PLAYLIST_PANEL_PLAYLIST_ID_FIELD);
        if (!playlistId.startsWith(YOUTUBE_MUSIC_ALBUM_PREFIX)) return null;

        List<AlbumProto.Field> fields = AlbumProto.parse(panel);
        List<String> songVideoIds = rememberTrackSongs(playlistId, panel, fields);

        // A tapped track played as its song is not in the album queue the server knows which
        // then selects the first entry. The entry id of the track still names its entry.
        String selectedEntryId = null;
        for (AlbumProto.Field field : fields) {
            if (field.number != PLAYLIST_PANEL_CONTENTS_FIELD || !field.isMessage()) continue;
            byte[] renderer = AlbumProto.bytesField(
                    AlbumProto.value(panel, field), PLAYLIST_PANEL_VIDEO_RENDERER_FIELD);
            if (!currentEntryId.isEmpty() && currentEntryId.equals(
                    AlbumProto.stringField(renderer, RENDERER_PLAYLIST_SET_VIDEO_ID_FIELD))) {
                selectedEntryId = currentEntryId;
                currentIndex[0] = AlbumProto.varintField(watchEndpointOf(renderer), WATCH_ENDPOINT_INDEX_FIELD);
                break;
            }
        }
        if (songVideoIds.isEmpty() && selectedEntryId == null) return null;

        final long startTime = System.currentTimeMillis();
        // The app waits for this response before it shows the queue, but it must never
        // freeze the screen while the songs are fetched.
        final long timeout = Utils.isCurrentlyOnMainThread() ? 0 : MAX_MILLISECONDS_TO_WAIT_FOR_SONGS;
        Map<String, byte[]> songs = AlbumRequests.getSongs(songVideoIds, timeout);

        ByteArrayOutputStream out = new ByteArrayOutputStream(panel.length);
        int[] replaced = {0};
        boolean changed = false;
        for (AlbumProto.Field field : fields) {
            byte[] entry = field.number == PLAYLIST_PANEL_CONTENTS_FIELD && field.isMessage()
                    ? rewriteEntry(AlbumProto.value(panel, field), songs, selectedEntryId, replaced)
                    : null;
            if (entry == null) {
                AlbumProto.writeRaw(out, panel, field);
            } else {
                AlbumProto.writeBytes(out, field.number, entry);
                changed = true;
            }
        }

        final int replacedCount = replaced[0];
        final long selectedIndex = currentIndex[0];
        Logger.printDebug(() -> "Album " + playlistId + ": replaced " + replacedCount + " of "
                + songVideoIds.size() + " music videos with their songs, current index: " + selectedIndex
                + ", waited: " + (System.currentTimeMillis() - startTime) + "ms on: "
                + Thread.currentThread().getName());
        return changed ? out.toByteArray() : null;
    }

    /**
     * Remembers the song of each music video of an album queue, so a tapped track of the album
     * can be played as its song.
     *
     * @return The video ids of the songs of the music videos.
     */
    static List<String> rememberTrackSongs(String playlistId, byte[] panel, List<AlbumProto.Field> fields) {
        List<String> songVideoIds = new ArrayList<>();
        for (AlbumProto.Field field : fields) {
            if (field.number != PLAYLIST_PANEL_CONTENTS_FIELD || !field.isMessage()) continue;

            byte[] renderer = AlbumProto.bytesField(
                    AlbumProto.value(panel, field), PLAYLIST_PANEL_VIDEO_RENDERER_FIELD);
            byte[] watchEndpoint = watchEndpointOf(renderer);
            String songVideoId = songVideoIdOf(watchEndpoint);
            if (songVideoId == null) continue;

            songVideoIds.add(songVideoId);
            trackSongs.put(new AlbumTrack(playlistId,
                            AlbumProto.varintField(watchEndpoint, WATCH_ENDPOINT_INDEX_FIELD)),
                    new TrackSong(AlbumProto.stringField(watchEndpoint, WATCH_ENDPOINT_VIDEO_ID_FIELD),
                            songVideoId));
        }
        return songVideoIds;
    }

    /**
     * @param selectedEntryId Entry to select, or null to leave the selection as is.
     * @param replaced        Counts the entries whose music video is replaced by the song.
     * @return The entry with its music video replaced by the song and its selection set, or null
     *         if it is left as is.
     */
    @Nullable
    private static byte[] rewriteEntry(byte[] entry, Map<String, byte[]> songs,
                                       @Nullable String selectedEntryId, int[] replaced) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(entry.length);
        boolean changed = false;
        for (AlbumProto.Field field : AlbumProto.parse(entry)) {
            if (field.number != PLAYLIST_PANEL_VIDEO_RENDERER_FIELD || !field.isMessage()) {
                AlbumProto.writeRaw(out, entry, field);
                continue;
            }
            byte[] renderer = AlbumProto.value(entry, field);
            byte[] rewritten = renderer;

            String songVideoId = songVideoIdOf(watchEndpointOf(renderer));
            byte[] songRenderer = songVideoId == null ? null : songs.get(songVideoId);
            if (songRenderer != null) {
                rewritten = songEntry(renderer, songRenderer);
                replaced[0]++;
            }
            if (selectedEntryId != null) {
                final long selected = selectedEntryId.equals(
                        AlbumProto.stringField(renderer, RENDERER_PLAYLIST_SET_VIDEO_ID_FIELD)) ? 1 : 0;
                if (AlbumProto.varintField(rewritten, RENDERER_SELECTED_FIELD) != selected) {
                    rewritten = AlbumProto.withVarintField(rewritten, RENDERER_SELECTED_FIELD, selected);
                }
            }

            if (rewritten == renderer) {
                AlbumProto.writeRaw(out, entry, field);
            } else {
                AlbumProto.writeBytes(out, field.number, rewritten);
                changed = true;
            }
        }
        return changed ? out.toByteArray() : null;
    }

    /**
     * @return The queue entry of the song, at the place of the music video in the album queue.
     */
    private static byte[] songEntry(byte[] videoRenderer, byte[] songRenderer) {
        byte[] videoWatchEndpoint = watchEndpointOf(videoRenderer);

        ByteArrayOutputStream out = new ByteArrayOutputStream(songRenderer.length);
        for (AlbumProto.Field field : AlbumProto.parse(songRenderer)) {
            if (field.number == RENDERER_SELECTED_FIELD
                    || field.number == RENDERER_PLAYLIST_SET_VIDEO_ID_FIELD) {
                continue;
            }
            if (field.number == RENDERER_NAVIGATION_ENDPOINT_FIELD && field.isMessage()) {
                AlbumProto.writeBytes(out, field.number,
                        withAlbumPlace(AlbumProto.value(songRenderer, field), videoWatchEndpoint));
            } else {
                AlbumProto.writeRaw(out, songRenderer, field);
            }
        }
        for (AlbumProto.Field field : AlbumProto.parse(videoRenderer)) {
            if (field.number == RENDERER_SELECTED_FIELD
                    || field.number == RENDERER_PLAYLIST_SET_VIDEO_ID_FIELD) {
                AlbumProto.writeRaw(out, videoRenderer, field);
            }
        }
        return out.toByteArray();
    }

    /**
     * @return The navigation endpoint of the song, playing at the place of the music video in the
     *         album.
     */
    private static byte[] withAlbumPlace(byte[] songNavigationEndpoint, @Nullable byte[] videoWatchEndpoint) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(songNavigationEndpoint.length);
        for (AlbumProto.Field field : AlbumProto.parse(songNavigationEndpoint)) {
            if (field.number != WATCH_ENDPOINT_FIELD || !field.isMessage() || videoWatchEndpoint == null) {
                AlbumProto.writeRaw(out, songNavigationEndpoint, field);
                continue;
            }
            byte[] songWatchEndpoint = AlbumProto.value(songNavigationEndpoint, field);
            ByteArrayOutputStream endpoint = new ByteArrayOutputStream(songWatchEndpoint.length);
            for (AlbumProto.Field endpointField : AlbumProto.parse(songWatchEndpoint)) {
                if (!isAlbumField(endpointField.number)) {
                    AlbumProto.writeRaw(endpoint, songWatchEndpoint, endpointField);
                }
            }
            for (AlbumProto.Field endpointField : AlbumProto.parse(videoWatchEndpoint)) {
                if (isAlbumField(endpointField.number)) {
                    AlbumProto.writeRaw(endpoint, videoWatchEndpoint, endpointField);
                }
            }
            AlbumProto.writeBytes(out, field.number, endpoint.toByteArray());
        }
        return out.toByteArray();
    }

    private static boolean isAlbumField(int number) {
        for (int albumField : WATCH_ENDPOINT_ALBUM_FIELDS) {
            if (albumField == number) return true;
        }
        return false;
    }

    /**
     * A tapped track of an album page names the music video, so it is played as the song when
     * the album queue was already seen.
     */
    @Nullable
    private static byte[] rewriteTappedTrack(byte[] watchEndpoint) {
        byte[] rewritten = rewriteKnownTrack(watchEndpoint);
        if (rewritten != null) {
            Logger.printDebug(() -> "Playing the song of tapped album track: "
                    + AlbumProto.stringField(watchEndpoint, WATCH_ENDPOINT_VIDEO_ID_FIELD));
        }
        return rewritten;
    }

    /**
     * @return The watch endpoint playing the song of the album track, or null if the track is not
     *         a music video of an album queue seen before.
     */
    @Nullable
    private static byte[] rewriteKnownTrack(byte[] watchEndpoint) {
        String playlistId = AlbumProto.stringField(watchEndpoint, WATCH_ENDPOINT_PLAYLIST_ID_FIELD);
        if (!playlistId.startsWith(YOUTUBE_MUSIC_ALBUM_PREFIX)) return null;

        TrackSong track = trackSongs.get(new AlbumTrack(playlistId,
                AlbumProto.varintField(watchEndpoint, WATCH_ENDPOINT_INDEX_FIELD)));
        String videoId = AlbumProto.stringField(watchEndpoint, WATCH_ENDPOINT_VIDEO_ID_FIELD);
        if (track == null || !track.videoId().equals(videoId) || isDownloaded(videoId)) return null;
        // Otherwise the album queue still holds the music video of the track.
        if (!AlbumRequests.isSongAvailable(track.songVideoId())) return null;

        return songWatchEndpoint(watchEndpoint, track.songVideoId());
    }

    /**
     * @return The watch endpoint with the song in place of the music video.
     */
    private static byte[] songWatchEndpoint(byte[] watchEndpoint, String songVideoId) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(watchEndpoint.length);
        for (AlbumProto.Field field : AlbumProto.parse(watchEndpoint)) {
            switch (field.number) {
                case WATCH_ENDPOINT_VIDEO_ID_FIELD ->
                        AlbumProto.writeString(out, field.number, songVideoId);
                // The streams it prefetches are the ones of the music video.
                case WATCH_ENDPOINT_ONESIE_CONFIG_FIELD -> {
                }
                case WATCH_ENDPOINT_PARAMS_FIELD -> AlbumProto.writeString(out, field.number,
                        withoutSongVideoId(AlbumProto.stringField(watchEndpoint, field.number)));
                case WATCH_ENDPOINT_MUSIC_SUPPORTED_CONFIGS_FIELD -> AlbumProto.writeBytes(out, field.number,
                        withSongVideoType(AlbumProto.value(watchEndpoint, field)));
                default -> AlbumProto.writeRaw(out, watchEndpoint, field);
            }
        }
        return out.toByteArray();
    }

    private static byte[] withSongVideoType(byte[] musicSupportedConfigs) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(musicSupportedConfigs.length);
        for (AlbumProto.Field field : AlbumProto.parse(musicSupportedConfigs)) {
            if (field.number != MUSIC_SUPPORTED_CONFIGS_MUSIC_CONFIG_FIELD || !field.isMessage()) {
                AlbumProto.writeRaw(out, musicSupportedConfigs, field);
                continue;
            }
            byte[] musicConfig = AlbumProto.value(musicSupportedConfigs, field);
            ByteArrayOutputStream config = new ByteArrayOutputStream(musicConfig.length);
            for (AlbumProto.Field configField : AlbumProto.parse(musicConfig)) {
                if (configField.number == MUSIC_CONFIG_VIDEO_TYPE_FIELD) {
                    AlbumProto.writeVarintField(config, configField.number, MUSIC_VIDEO_TYPE_ATV);
                } else {
                    AlbumProto.writeRaw(config, musicConfig, configField);
                }
            }
            AlbumProto.writeBytes(out, field.number, config.toByteArray());
        }
        return out.toByteArray();
    }

    @Nullable
    private static byte[] watchEndpointOf(@Nullable byte[] renderer) {
        return AlbumProto.bytesField(AlbumProto.bytesField(renderer, RENDERER_NAVIGATION_ENDPOINT_FIELD),
                WATCH_ENDPOINT_FIELD);
    }

    /**
     * @return The video id of the album song of a music video, or null if the endpoint is not a
     *         music video of an album, or the music video is saved for offline playback.
     */
    @Nullable
    private static String songVideoIdOf(@Nullable byte[] watchEndpoint) {
        if (watchEndpoint == null) return null;

        byte[] musicConfig = AlbumProto.bytesField(AlbumProto.bytesField(watchEndpoint,
                WATCH_ENDPOINT_MUSIC_SUPPORTED_CONFIGS_FIELD), MUSIC_SUPPORTED_CONFIGS_MUSIC_CONFIG_FIELD);
        if (AlbumProto.varintField(musicConfig, MUSIC_CONFIG_VIDEO_TYPE_FIELD) != MUSIC_VIDEO_TYPE_OMV) {
            return null;
        }

        String params = AlbumProto.stringField(watchEndpoint, WATCH_ENDPOINT_PARAMS_FIELD);
        if (params.isEmpty()) return null;
        String songVideoId = AlbumProto.stringField(decodeParams(params), PARAMS_SONG_VIDEO_ID_FIELD);
        if (!BaseVideoInformation.isVideoId(songVideoId)) return null;

        // A saved music video plays offline, and the song would not.
        if (isDownloaded(AlbumProto.stringField(watchEndpoint, WATCH_ENDPOINT_VIDEO_ID_FIELD))) return null;
        return songVideoId;
    }

    private static boolean isDownloaded(String videoId) {
        return OfflineVideoExporter.isExportable(videoId);
    }

    /**
     * The params are URL safe base64, with the padding URL encoded.
     */
    private static byte[] decodeParams(String params) {
        String base64 = params.replace(URL_ENCODED_PADDING, BASE64_PADDING)
                .replace('+', '-')
                .replace('/', '_');
        return Base64.decode(base64, Base64.URL_SAFE);
    }

    /**
     * @return The params, without the video id of the song, as a song of the album has them.
     */
    private static String withoutSongVideoId(String params) {
        if (params.isEmpty()) return params;

        byte[] decoded = decodeParams(params);
        ByteArrayOutputStream out = new ByteArrayOutputStream(decoded.length);
        for (AlbumProto.Field field : AlbumProto.parse(decoded)) {
            if (field.number != PARAMS_SONG_VIDEO_ID_FIELD) AlbumProto.writeRaw(out, decoded, field);
        }
        String encoded = Base64.encodeToString(out.toByteArray(), Base64.URL_SAFE | Base64.NO_WRAP);
        return encoded.replace(BASE64_PADDING, URL_ENCODED_PADDING);
    }

    /**
     * Filled in by the patch.
     *
     * @return The serialized protobuf message of the app.
     */
    private static byte[] serializeMessage(Object message) {
        throw new UnsupportedOperationException();
    }

    /**
     * Filled in by the patch.
     *
     * @param defaultInstance Default instance of the protobuf message type to parse.
     * @param extensionRegistry Registry of the extensions of the app, such as the watch endpoint,
     *                          which are otherwise parsed as unknown fields.
     */
    private static Object parseMessage(Object defaultInstance, byte[] message, Object extensionRegistry) {
        throw new UnsupportedOperationException();
    }

    /**
     * Filled in by the patch.
     */
    private static Object extensionRegistry() {
        throw new UnsupportedOperationException();
    }

    /**
     * Filled in by the patch.
     */
    private static Object watchNextResponseDefaultInstance() {
        throw new UnsupportedOperationException();
    }

    /**
     * Filled in by the patch.
     */
    private static Object commandDefaultInstance() {
        throw new UnsupportedOperationException();
    }
}
