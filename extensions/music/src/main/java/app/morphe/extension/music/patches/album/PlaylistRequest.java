/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2556
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.album;

import android.content.Context;

import androidx.annotation.GuardedBy;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.requests.Requester;
import app.morphe.extension.shared.requests.Route;
import app.morphe.extension.shared.spoof.ClientType;

/**
 * Fetches the song version of every track of an album with a single InnerTube request,
 * so only the first track played from an album has to wait for the network.
 */
public final class PlaylistRequest {

    private static final String YT_API_URL = "https://youtubei.googleapis.com/youtubei/v1/";

    // The VR client is used because it needs neither a DroidGuard PoToken nor authentication.
    private static final ClientType CLIENT = ClientType.ANDROID_VR_SABR;

    // English forced so parseResponse can rely on the "Album" / "Song" playlist title prefix.
    private static final String LANGUAGE = "en";

    private static final Route.CompiledRoute GET_PLAYLIST_PAGE_ROUTE = new Route(
            Route.Method.POST,
            "next?fields=contents.singleColumnWatchNextResults.playlist.playlist&prettyPrint=false"
    ).compile();

    private static final int CONNECTION_TIMEOUT_MILLISECONDS = 8 * 1000;

    private static final int MAX_FETCH_ATTEMPTS = 3;
    private static final long RETRY_BACKOFF_MILLISECONDS = 500;

    /**
     * Album contents never change, so a fetched album is kept for a whole listening session.
     */
    private static final long CACHE_RETENTION_TIME_MILLISECONDS = 30 * 60 * 1000L;

    private static final int NUMBER_OF_ALBUMS_TO_CACHE = 5;

    private static final Song[] NO_SONGS = new Song[0];

    /**
     * An album track as the album itself lists it, rather than as the queue plays it.
     */
    public record Song(String videoId, String title, String artist, int durationSeconds) {
    }

    private static final TimeZone TIME_ZONE = TimeZone.getDefault();
    private static final int UTC_OFFSET_MINUTES = TIME_ZONE.getOffset(new Date().getTime()) / 60000;

    @GuardedBy("itself")
    private static final Map<String, PlaylistRequest> cache =
            new LinkedHashMap<>(NUMBER_OF_ALBUMS_TO_CACHE, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, PlaylistRequest> eldest) {
                    return size() > NUMBER_OF_ALBUMS_TO_CACHE;
                }
            };

    private final String playlistId;
    private final long timeFetched = System.currentTimeMillis();

    /**
     * Album tracks indexed by their position, or null while the fetch is still running.
     */
    @Nullable
    private volatile Song[] songs;

    private final CountDownLatch fetchCompleted = new CountDownLatch(1);

    private PlaylistRequest(@NonNull String videoId, @NonNull String playlistId) {
        this.playlistId = playlistId;
        Utils.runOnBackgroundThread(() -> {
            try {
                songs = fetch(videoId, playlistId);
            } finally {
                fetchCompleted.countDown();
            }
        });
    }

    /**
     * Starts fetching the album, unless it is already cached or a fetch is in flight.
     */
    public static void fetchRequestIfNeeded(@NonNull String videoId, @NonNull String playlistId) {
        synchronized (cache) {
            long now = System.currentTimeMillis();
            Iterator<Map.Entry<String, PlaylistRequest>> it = cache.entrySet().iterator();
            while (it.hasNext()) {
                PlaylistRequest entry = it.next().getValue();
                if (entry.isExpired(now)) {
                    Logger.printDebug(() -> "Removing expired album: " + entry.playlistId);
                    it.remove();
                }
            }
            if (!cache.containsKey(playlistId)) {
                cache.put(playlistId, new PlaylistRequest(videoId, playlistId));
            }
        }
    }

    @Nullable
    public static PlaylistRequest getRequestForPlaylistId(@NonNull String playlistId) {
        synchronized (cache) {
            return cache.get(playlistId);
        }
    }

    private boolean isExpired(long now) {
        Song[] ids = songs;
        // Keep in flight requests, and drop failed ones at once so the next track can retry.
        if (ids == null) return false;
        if (ids.length == 0) return true;
        return now - timeFetched > CACHE_RETENTION_TIME_MILLISECONDS;
    }

    /**
     * Waits for the album to be fetched, and returns at once if it already is.
     * Must not be called from the main thread.
     *
     * @return The album track at the given position, or null if the album could not be
     *         fetched in time.
     */
    @Nullable
    public Song awaitSong(int playlistIndex, long timeoutMilliseconds) {
        try {
            if (!fetchCompleted.await(timeoutMilliseconds, TimeUnit.MILLISECONDS)) {
                Logger.printDebug(() -> "Timed out waiting for album: " + playlistId);
                return null;
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return null;
        }

        Song[] albumSongs = songs;
        if (albumSongs == null || playlistIndex < 0 || playlistIndex >= albumSongs.length) {
            return null;
        }
        return albumSongs[playlistIndex];
    }

    @NonNull
    private static Song[] fetch(@NonNull String videoId, @NonNull String playlistId) {
        for (int attempt = 1; ; attempt++) {
            JSONObject playlistJson = sendRequest(videoId, playlistId);
            if (playlistJson != null) {
                return parseResponse(playlistJson);
            }
            if (attempt >= MAX_FETCH_ATTEMPTS) {
                return NO_SONGS;
            }
            try {
                //noinspection BusyWait
                Thread.sleep(RETRY_BACKOFF_MILLISECONDS * attempt);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return NO_SONGS;
            }
        }
    }

    @Nullable
    private static JSONObject sendRequest(@NonNull String videoId, @NonNull String playlistId) {
        long startTime = System.currentTimeMillis();
        Logger.printDebug(() -> "Fetching album request for: " + playlistId);

        try {
            HttpURLConnection connection = openInnerTubeConnection();
            byte[] body = buildRequestBody(videoId, playlistId);
            connection.setFixedLengthStreamingMode(body.length);
            connection.getOutputStream().write(body);

            int responseCode = connection.getResponseCode();
            if (responseCode == 200) {
                return Requester.parseJSONObject(connection);
            }

            // Pulled out of the lambda because Supplier cannot propagate the checked IOException.
            final String responseMessage = connection.getResponseMessage();
            Logger.printInfo(() -> "InnerTube request failed: " + responseCode + " " + responseMessage);
        } catch (SocketTimeoutException ex) {
            Logger.printInfo(() -> "Connection timeout", ex);
        } catch (IOException ex) {
            Logger.printInfo(() -> "Network error", ex);
        } catch (Exception ex) {
            Logger.printException(() -> "sendRequest failed", ex);
        } finally {
            Logger.printDebug(() -> "album: " + playlistId + " took: "
                    + (System.currentTimeMillis() - startTime) + "ms");
        }

        return null;
    }

    // Reads the locale the app itself is configured with (which may differ from the system locale
    // when the user picks a different language inside YT Music). Falls back to system locale before
    // the application context is available.
    @NonNull
    private static Locale currentAppLocale() {
        try {
            Context context = Utils.getContext();
            if (context != null) {
                return context.getResources().getConfiguration().getLocales().get(0);
            }
        } catch (Exception ignored) {
        }
        return Locale.getDefault();
    }

    private static HttpURLConnection openInnerTubeConnection() throws IOException {
        HttpURLConnection connection =
                Requester.getConnectionFromCompiledRoute(YT_API_URL, GET_PLAYLIST_PAGE_ROUTE);

        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("User-Agent", CLIENT.userAgent);
        // Not a typo. "Client-Name" uses the client type id.
        connection.setRequestProperty("X-YouTube-Client-Name", String.valueOf(CLIENT.id));
        connection.setRequestProperty("X-YouTube-Client-Version", CLIENT.clientVersion);
        connection.setRequestProperty("X-GOOG-API-FORMAT-VERSION", "2");

        connection.setUseCaches(false);
        connection.setDoOutput(true);
        connection.setConnectTimeout(CONNECTION_TIMEOUT_MILLISECONDS);
        connection.setReadTimeout(CONNECTION_TIMEOUT_MILLISECONDS);

        return connection;
    }

    private static byte[] buildRequestBody(@NonNull String videoId, @NonNull String playlistId) {
        JSONObject innerTubeBody = new JSONObject();
        try {
            JSONObject client = new JSONObject();
            client.put("deviceMake", CLIENT.deviceMake);
            client.put("deviceModel", CLIENT.deviceModel);
            client.put("clientName", CLIENT.clientName);
            client.put("clientVersion", CLIENT.clientVersion);
            client.put("osName", CLIENT.osName);
            client.put("osVersion", CLIENT.osVersion);
            client.put("androidSdkVersion", CLIENT.androidSdkVersion);
            client.put("hl", LANGUAGE);
            client.put("gl", currentAppLocale().getCountry());
            client.put("timeZone", TIME_ZONE.getID());
            client.put("utcOffsetMinutes", String.valueOf(UTC_OFFSET_MINUTES));

            JSONObject context = new JSONObject();
            context.put("client", client);

            innerTubeBody.put("context", context);
            innerTubeBody.put("contentCheckOk", true);
            innerTubeBody.put("racyCheckOk", true);
            innerTubeBody.put("videoId", videoId);
            innerTubeBody.put("playlistId", playlistId);
        } catch (JSONException e) {
            Logger.printException(() -> "Failed to create application innerTubeBody", e);
        }
        return innerTubeBody.toString().getBytes(StandardCharsets.UTF_8);
    }

    @NonNull
    private static Song[] parseResponse(@NonNull JSONObject playlistJson) {
        try {
            JSONObject singleColumnWatchNextResults = playlistJson
                    .getJSONObject("contents")
                    .getJSONObject("singleColumnWatchNextResults");

            if (!singleColumnWatchNextResults.has("playlist")) {
                return NO_SONGS;
            }

            JSONObject playlistObj = singleColumnWatchNextResults
                    .getJSONObject("playlist")
                    .getJSONObject("playlist");

            // Top Songs reuses the album's playlistId - the response is only an album when the
            // playlist title starts with "Album" (Song = Top Songs). Hence the forced hl=en above.
            String title = playlistObj.optString("title", "");
            if (!title.startsWith("Album")) {
                return NO_SONGS;
            }

            JSONArray contents = playlistObj.getJSONArray("contents");
            final int length = contents.length();
            Song[] albumSongs = new Song[length];
            for (int i = 0; i < length; i++) {
                albumSongs[i] = parseSong(contents.opt(i));
            }
            return albumSongs;
        } catch (JSONException e) {
            Logger.printException(() -> "Fetch failed while processing response data for response: "
                    + playlistJson, e);
        }
        return NO_SONGS;
    }

    @Nullable
    private static Song parseSong(@Nullable Object entry) {
        if (!(entry instanceof JSONObject)) {
            return null;
        }
        JSONObject renderer = ((JSONObject) entry).optJSONObject("playlistPanelVideoRenderer");
        if (renderer == null) {
            return null;
        }
        JSONObject navigationEndpoint = renderer.optJSONObject("navigationEndpoint");
        JSONObject watchEndpoint = navigationEndpoint == null
                ? null
                : navigationEndpoint.optJSONObject("watchEndpoint");
        if (watchEndpoint == null) {
            return null;
        }
        String videoId = watchEndpoint.optString("videoId", "");
        if (videoId.isEmpty()) {
            return null;
        }
        return new Song(videoId, parseRuns(renderer, "title"),
                parseRuns(renderer, "longBylineText"),
                parseDuration(parseRuns(renderer, "lengthText")));
    }

    /**
     * @return Seconds of a "m:ss" or "h:mm:ss" length, or zero if it cannot be read.
     */
    private static int parseDuration(@NonNull String lengthText) {
        int seconds = 0;
        for (String part : lengthText.split(":")) {
            try {
                seconds = seconds * 60 + Integer.parseInt(part.trim());
            } catch (NumberFormatException ex) {
                return 0;
            }
        }
        return seconds;
    }

    @NonNull
    private static String parseRuns(@NonNull JSONObject renderer, @NonNull String name) {
        JSONObject text = renderer.optJSONObject(name);
        JSONArray runs = text == null ? null : text.optJSONArray("runs");
        JSONObject first = runs == null ? null : runs.optJSONObject(0);
        return first == null ? "" : first.optString("text", "");
    }
}
