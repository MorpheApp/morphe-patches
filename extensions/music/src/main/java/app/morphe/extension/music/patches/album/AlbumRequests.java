/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5141
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.album;

import android.os.Build;

import androidx.annotation.GuardedBy;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.requests.Requester;
import app.morphe.extension.shared.requests.Route;

/**
 * Fetches what the album queue changes need, in the protobuf format of the app itself.
 *
 * <p>The songs are fetched as the queue entries the app shows for them, with a single request for
 * every song of an album. An album page fetches its album queue as soon as it opens, so its tracks
 * are played as songs from the very first tap.
 */
final class AlbumRequests {

    private static final String YT_API_URL = "https://youtubei.googleapis.com/youtubei/v1/";
    private static final String YT_MUSIC_API_URL = "https://music.youtube.com/youtubei/v1/";

    // Only the queue entries, without the entity updates that make up most of the response.
    private static final Route.CompiledRoute GET_QUEUE_ROUTE = new Route(
            Route.Method.POST,
            "music/get_queue?alt=proto&fields=queueDatas"
    ).compile();

    // Only what the queue changes read from the album queue.
    private static final Route.CompiledRoute ALBUM_QUEUE_ROUTE = new Route(
            Route.Method.POST,
            "next?alt=proto&fields=contents.singleColumnMusicWatchNextResultsRenderer.tabbedRenderer"
                    + ".watchNextTabbedResultsRenderer.tabs.tabRenderer.content.musicQueueRenderer.content"
                    + ".playlistPanelRenderer(playlistId,contents.playlistPanelVideoRenderer.navigationEndpoint"
                    + ".watchEndpoint(videoId,index,params,watchEndpointMusicSupportedConfigs))"
    ).compile();

    // The web client is the one that names the playlist of an album page.
    private static final Route.CompiledRoute ALBUM_PAGE_PLAYLIST_ROUTE = new Route(
            Route.Method.POST,
            "browse?prettyPrint=false&fields=microformat.microformatDataRenderer.urlCanonical"
    ).compile();

    // The client of the app, so the entries have the fields of the queue entries of the app.
    private static final String CLIENT_NAME = "ANDROID_MUSIC";
    private static final int CLIENT_ID = 21;
    private static final String OS_NAME = "Android";

    private static final String WEB_CLIENT_NAME = "WEB_REMIX";
    private static final int WEB_CLIENT_ID = 67;
    private static final String WEB_USER_AGENT = "Mozilla/5.0";
    /**
     * Web client version for devices with a wrong date, whose version from the date is refused.
     */
    private static final String WEB_CLIENT_VERSION = "1.20260914.01.00";
    private static final long DAY_MILLISECONDS = 24 * 60 * 60 * 1000L;
    private static final String PLAYLIST_URL_PARAMETER = "list=";

    private static final int CONNECTION_TIMEOUT_MILLISECONDS = 5 * 1000;

    /**
     * Field numbers of the music/get_queue response.
     */
    private static final int RESPONSE_QUEUE_DATAS_FIELD = 3;
    private static final int QUEUE_DATA_CONTENT_FIELD = 1;
    private static final int PLAYLIST_PANEL_VIDEO_RENDERER_FIELD = 51779701;
    private static final int RENDERER_VIDEO_ID_FIELD = 10;

    private static final int NUMBER_OF_SONGS_TO_CACHE = 300;

    /**
     * An album is fetched again only after this long.
     */
    private static final long ALBUM_REFETCH_MILLISECONDS = 30 * 60 * 1000L;
    private static final int NUMBER_OF_ALBUMS_TO_TRACK = 50;

    /**
     * Queue entry of each song, as the serialized PlaylistPanelVideoRenderer.
     */
    private static final Map<String, byte[]> songs = Collections.synchronizedMap(
            Utils.createSizeRestrictedMap(NUMBER_OF_SONGS_TO_CACHE));

    /**
     * Fetches in flight, by the video id of each song they fetch.
     */
    @GuardedBy("inFlight")
    private static final Map<String, Future<?>> inFlight = new HashMap<>();

    /**
     * When each album page or album queue was last fetched, by its browse or playlist id.
     */
    private static final Map<String, Long> fetchedAlbums = Collections.synchronizedMap(
            Utils.createSizeRestrictedMap(NUMBER_OF_ALBUMS_TO_TRACK));

    private AlbumRequests() {
    }

    /**
     * Fetches the album queue of an album page that just opened, unless it was fetched recently.
     */
    static void fetchAlbumPage(String browseId) {
        if (isFetchedRecently(browseId)) return;

        Utils.runOnBackgroundThread(() -> {
            String playlistId = fetchAlbumPagePlaylistId(browseId);
            if (playlistId != null) fetchAlbumQueue(playlistId);
        });
    }

    /**
     * Fetches the album queue and its songs, unless they were fetched recently.
     */
    static void fetchAlbum(String playlistId) {
        if (isFetchedRecently(playlistId)) return;

        Utils.runOnBackgroundThread(() -> fetchAlbumQueue(playlistId));
    }

    /**
     * @return If the album was fetched recently. Otherwise, it is marked as fetched now.
     */
    private static boolean isFetchedRecently(String id) {
        synchronized (fetchedAlbums) {
            final long now = System.currentTimeMillis();
            Long fetched = fetchedAlbums.get(id);
            if (fetched != null && now - fetched < ALBUM_REFETCH_MILLISECONDS) return true;
            fetchedAlbums.put(id, now);
            return false;
        }
    }

    private static void fetchAlbumQueue(String playlistId) {
        // An album page and a tap of one of its tracks both ask for the same album.
        fetchedAlbums.put(playlistId, System.currentTimeMillis());
        try {
            JSONObject body = createAppRequestBody();
            body.put("playlistId", playlistId);
            byte[] response = post(YT_API_URL, ALBUM_QUEUE_ROUTE, body, true);
            if (response == null) {
                fetchedAlbums.remove(playlistId);
                return;
            }

            List<String> songVideoIds = PlayAlbumSongsPatch.rememberAlbumQueue(response);
            Logger.printDebug(() -> "Album " + playlistId + " has " + songVideoIds.size()
                    + " music videos, fetching their songs");
            getSongs(songVideoIds, CONNECTION_TIMEOUT_MILLISECONDS);
        } catch (Exception ex) {
            fetchedAlbums.remove(playlistId);
            Logger.printException(() -> "fetchAlbumQueue failure", ex);
        }
    }

    @Nullable
    private static String fetchAlbumPagePlaylistId(String browseId) {
        try {
            for (String version : new String[]{currentWebClientVersion(), WEB_CLIENT_VERSION}) {
                JSONObject client = new JSONObject();
                client.put("clientName", WEB_CLIENT_NAME);
                client.put("clientVersion", version);
                JSONObject body = new JSONObject();
                body.put("context", new JSONObject().put("client", client));
                body.put("browseId", browseId);

                byte[] response = post(YT_MUSIC_API_URL, ALBUM_PAGE_PLAYLIST_ROUTE, body, false);
                if (response == null) continue;

                JSONObject microformat = new JSONObject(new String(response, StandardCharsets.UTF_8))
                        .optJSONObject("microformat");
                JSONObject data = microformat == null ? null : microformat.optJSONObject("microformatDataRenderer");
                String url = data == null ? "" : data.optString("urlCanonical", "");
                final int start = url.indexOf(PLAYLIST_URL_PARAMETER);
                if (start < 0) break;

                String playlistId = url.substring(start + PLAYLIST_URL_PARAMETER.length()).split("&")[0];
                if (playlistId.startsWith(PlayAlbumSongsPatch.YOUTUBE_MUSIC_ALBUM_PREFIX)) {
                    return playlistId;
                }
                break;
            }
        } catch (Exception ex) {
            Logger.printInfo(() -> "Album page playlist not found: " + browseId, ex);
        }
        fetchedAlbums.remove(browseId);
        return null;
    }

    /**
     * @return Client version from yesterday, as old ones can be refused at any time.
     */
    private static String currentWebClientVersion() {
        SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd", Locale.ROOT);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return "1." + format.format(new Date(System.currentTimeMillis() - DAY_MILLISECONDS)) + ".01.00";
    }

    /**
     * @return If the queue entry of the song is cached.
     */
    static boolean isSongAvailable(String videoId) {
        return songs.containsKey(videoId);
    }

    /**
     * Fetches the songs that are not cached, and waits for them up to the given time.
     * Must not wait on the main thread.
     *
     * @return The queue entry of each song that is available, by video id.
     */
    static Map<String, byte[]> getSongs(Collection<String> videoIds, long timeoutMilliseconds) {
        Set<Future<?>> pending = new LinkedHashSet<>();
        List<String> missing = new ArrayList<>();
        synchronized (inFlight) {
            for (String videoId : new LinkedHashSet<>(videoIds)) {
                if (songs.containsKey(videoId)) continue;
                Future<?> fetch = inFlight.get(videoId);
                if (fetch != null) {
                    pending.add(fetch);
                } else {
                    missing.add(videoId);
                }
            }
            if (!missing.isEmpty()) {
                Future<?> fetch = Utils.submitOnBackgroundThread(() -> {
                    try {
                        fetch(missing);
                    } finally {
                        synchronized (inFlight) {
                            for (String videoId : missing) inFlight.remove(videoId);
                        }
                    }
                    return null;
                });
                for (String videoId : missing) inFlight.put(videoId, fetch);
                pending.add(fetch);
            }
        }

        final long deadline = System.currentTimeMillis() + timeoutMilliseconds;
        for (Future<?> fetch : pending) {
            final long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) break;
            try {
                fetch.get(remaining, TimeUnit.MILLISECONDS);
            } catch (TimeoutException ex) {
                Logger.printDebug(() -> "Timed out waiting for album songs");
                break;
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            } catch (ExecutionException ex) {
                Logger.printInfo(() -> "Album songs fetch failed", ex);
            }
        }

        Map<String, byte[]> available = new HashMap<>();
        for (String videoId : videoIds) {
            byte[] song = songs.get(videoId);
            if (song != null) available.put(videoId, song);
        }
        return available;
    }

    private static void fetch(List<String> videoIds) {
        final long startTime = System.currentTimeMillis();
        try {
            JSONObject body = createAppRequestBody();
            body.put("videoIds", new JSONArray(videoIds));
            byte[] response = post(YT_API_URL, GET_QUEUE_ROUTE, body, true);
            if (response == null) return;

            final int found = parseResponse(response);
            Logger.printDebug(() -> "Album songs fetched: " + found + " of " + videoIds.size()
                    + " took: " + (System.currentTimeMillis() - startTime) + "ms");
        } catch (Exception ex) {
            Logger.printException(() -> "Album songs fetch failure", ex);
        }
    }

    /**
     * @return The response, or null if the request failed.
     */
    @Nullable
    private static byte[] post(String apiUrl, Route.CompiledRoute route, JSONObject body, boolean isAppClient) {
        try {
            HttpURLConnection connection = Requester.getConnectionFromCompiledRoute(apiUrl, route);
            connection.setRequestProperty("Content-Type", "application/json");
            if (isAppClient) {
                final String appVersion = Utils.getAppVersionName();
                connection.setRequestProperty("User-Agent", "com.google.android.apps.youtube.music/"
                        + appVersion + " (Linux; U; Android " + Build.VERSION.RELEASE + ") gzip");
                // Not a typo. "Client-Name" uses the client type id.
                connection.setRequestProperty("X-YouTube-Client-Name", String.valueOf(CLIENT_ID));
                connection.setRequestProperty("X-YouTube-Client-Version", appVersion);
            } else {
                connection.setRequestProperty("User-Agent", WEB_USER_AGENT);
                connection.setRequestProperty("X-YouTube-Client-Name", String.valueOf(WEB_CLIENT_ID));
            }
            connection.setUseCaches(false);
            connection.setDoOutput(true);
            connection.setConnectTimeout(CONNECTION_TIMEOUT_MILLISECONDS);
            connection.setReadTimeout(CONNECTION_TIMEOUT_MILLISECONDS);

            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            connection.getOutputStream().write(bytes);

            final int responseCode = connection.getResponseCode();
            if (responseCode != Requester.HTTP_STATUS_CODE_SUCCESS) {
                Logger.printInfo(() -> "Album request failed: " + responseCode);
                connection.disconnect();
                return null;
            }
            byte[] response = Requester.parseBytes(connection);
            connection.disconnect();
            return response;
        } catch (IOException ex) {
            Logger.printInfo(() -> "Album request network error", ex);
            return null;
        } catch (Exception ex) {
            Logger.printException(() -> "Album request failure", ex);
            return null;
        }
    }

    private static JSONObject createAppRequestBody() throws JSONException {
        Locale locale = Requester.getAppLocale();

        JSONObject client = new JSONObject();
        client.put("clientName", CLIENT_NAME);
        client.put("clientVersion", Utils.getAppVersionName());
        client.put("osName", OS_NAME);
        client.put("osVersion", Build.VERSION.RELEASE);
        client.put("androidSdkVersion", Build.VERSION.SDK_INT);
        // The titles of the songs are shown in the queue, so they use the language of the app.
        client.put("hl", locale.getLanguage());
        if (!locale.getCountry().isEmpty()) {
            client.put("gl", locale.getCountry());
        }

        JSONObject body = new JSONObject();
        body.put("context", new JSONObject().put("client", client));
        return body;
    }

    /**
     * @return The number of songs found.
     */
    private static int parseResponse(byte[] response) {
        int found = 0;
        for (AlbumProto.Field queueData : AlbumProto.parse(response)) {
            if (queueData.number != RESPONSE_QUEUE_DATAS_FIELD || !queueData.isMessage()) continue;

            byte[] content = AlbumProto.bytesField(
                    AlbumProto.value(response, queueData), QUEUE_DATA_CONTENT_FIELD);
            byte[] renderer = AlbumProto.bytesField(content, PLAYLIST_PANEL_VIDEO_RENDERER_FIELD);
            String videoId = AlbumProto.stringField(renderer, RENDERER_VIDEO_ID_FIELD);
            if (renderer == null || videoId.isEmpty()) continue;

            songs.put(videoId, renderer);
            found++;
        }
        return found;
    }
}
