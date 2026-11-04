/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4398
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.music.patches.downloads;

import android.os.Build;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.innertube.utils.AuthUtils;
import app.morphe.extension.shared.requests.Requester;

/**
 * Lists the tracks of a playlist or an album, following every page.
 * <p>
 * Public playlists and albums are listed without the account, like the web player does.
 * Liked music and private playlists need the account, so they are listed as the app itself,
 * with the account headers that {@link AuthUtils} keeps from the requests of the app.
 */
final class PlaylistTracksRequest {

    /**
     * @param videoIds The tracks in playlist order.
     * @param complete If every page was listed. A page that could not be loaded ends the listing early.
     */
    record Result(List<String> videoIds, boolean complete) {
    }

    /** The playlist needs the account, and the app has not made a request with it yet. */
    static final Result NEEDS_ACCOUNT = new Result(Collections.emptyList(), false);

    private static final String WEB_BROWSE_URL =
            "https://music.youtube.com/youtubei/v1/browse?prettyPrint=false";
    private static final String WEB_CLIENT_NAME = "WEB_REMIX";
    private static final String WEB_CLIENT_ID = "67";
    private static final String WEB_CLIENT_VERSION = "1.20260914.01.00";
    private static final String WEB_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36";

    private static final String APP_BROWSE_URL =
            "https://youtubei.googleapis.com/youtubei/v1/browse?prettyPrint=false";
    private static final String APP_CLIENT_NAME = "ANDROID_MUSIC";
    private static final String APP_CLIENT_ID = "21";

    private static final int CONNECT_TIMEOUT_MILLISECONDS = 10_000;
    private static final int READ_TIMEOUT_MILLISECONDS = 20_000;
    private static final int MAX_FETCH_ATTEMPTS = 4;
    private static final long RETRY_BACKOFF_MILLISECONDS = 2000;

    /**
     * A page has about 100 tracks, so this stops a page loop the server never ends,
     * long after the largest playlist allowed (5000 tracks) is listed.
     */
    private static final int MAX_PAGES = 200;

    /** Tracks that are no longer available are shown greyed out, and cannot be downloaded. */
    private static final String GREYED_OUT_POLICY = "MUSIC_ITEM_RENDERER_DISPLAY_POLICY_GREY_OUT";

    private PlaylistTracksRequest() {
    }

    /**
     * @param browseId Browse id of the playlist page, such as 'VLLM' for Liked music,
     *                 or 'MPREb_...' for an album.
     * @param progress Called after each page with the number of tracks listed so far.
     * @return The tracks, {@link #NEEDS_ACCOUNT}, or null if the first page could not be loaded.
     */
    @Nullable
    static Result fetchVideoIds(String browseId, @Nullable PageCallback progress) {
        // Liked music is always empty without the account.
        final boolean needsAccount = browseId.equals("VLLM");

        if (!needsAccount) {
            Result result = fetchVideoIds(browseId, progress, false);
            if (result != null && !result.videoIds().isEmpty()) return result;
            // A private playlist looks like an empty one without the account.
            if (browseId.startsWith("MPRE")) return result;
            Logger.printDebug(() -> "No track listed without the account for: " + browseId);
        }

        if (AuthUtils.getAuthorization().isEmpty()) {
            Logger.printInfo(() -> "The account of the app is not known yet, cannot list: " + browseId);
            return NEEDS_ACCOUNT;
        }
        return fetchVideoIds(browseId, progress, true);
    }

    @Nullable
    private static Result fetchVideoIds(String browseId, @Nullable PageCallback progress, boolean asApp) {
        Set<String> videoIds = new LinkedHashSet<>();
        String continuation;
        int pages = 1;

        try {
            JSONObject page = send(newRequestBody(asApp).put("browseId", browseId), asApp);
            if (page == null) return null;

            continuation = parseTracks(firstShelf(page), videoIds);
            if (videoIds.isEmpty()) {
                Logger.printInfo(() -> "First page of " + browseId + " has no track, keys: "
                        + page.names());
            }

            while (continuation != null && pages < MAX_PAGES) {
                if (progress != null) progress.onPage(videoIds.size());

                JSONObject continuationPage = send(
                        newRequestBody(asApp).put("continuation", continuation), asApp);
                if (continuationPage == null) {
                    // The tracks listed so far are still downloaded.
                    final int pageNumber = pages + 1;
                    Logger.printInfo(() -> "Could not load page " + pageNumber + " of " + browseId
                            + ", stopping at " + videoIds.size() + " tracks");
                    break;
                }
                continuation = parseContinuation(continuationPage, videoIds);
                pages++;
            }
        } catch (JSONException ex) {
            Logger.printException(() -> "Could not build the playlist request", ex);
            return null;
        }

        final boolean complete = continuation == null;
        final int pageCount = pages;
        Logger.printDebug(() -> "Listed " + videoIds.size() + " tracks of " + browseId + " in " + pageCount
                + " pages" + (asApp ? " with the account" : "") + (complete ? "" : ", incomplete"));
        return new Result(new ArrayList<>(videoIds), complete);
    }

    interface PageCallback {
        void onPage(int tracksListed);
    }

    /**
     * The tracks of the first page are in the playlist shelf for playlists,
     * and in the music shelf for albums. Suggestions are in later shelves.
     */
    @Nullable
    private static JSONObject firstShelf(JSONObject page) {
        JSONObject shelf = findObject(page, "musicPlaylistShelfRenderer");
        return shelf != null ? shelf : findObject(page, "musicShelfRenderer");
    }

    /**
     * @return The continuation of the next page, or null if this is the last page.
     */
    @Nullable
    private static String parseTracks(@Nullable JSONObject shelf, Set<String> videoIds) {
        if (shelf == null) return null;

        String continuation = parseItems(shelf.optJSONArray("contents"), videoIds);
        return continuation != null ? continuation : legacyContinuation(shelf);
    }

    @Nullable
    private static String parseContinuation(JSONObject page, Set<String> videoIds) {
        JSONArray actions = page.optJSONArray("onResponseReceivedActions");
        if (actions != null) {
            for (int i = 0; i < actions.length(); i++) {
                JSONObject action = actions.optJSONObject(i);
                JSONObject append = action == null
                        ? null
                        : action.optJSONObject("appendContinuationItemsAction");
                if (append != null) {
                    return parseItems(append.optJSONArray("continuationItems"), videoIds);
                }
            }
        }

        JSONObject contents = page.optJSONObject("continuationContents");
        if (contents != null) {
            JSONObject shelf = contents.optJSONObject("musicPlaylistShelfContinuation");
            if (shelf == null) shelf = contents.optJSONObject("musicShelfContinuation");
            return parseTracks(shelf, videoIds);
        }
        return null;
    }

    /**
     * @return The continuation of the next page, if the last item is one.
     */
    @Nullable
    private static String parseItems(@Nullable JSONArray items, Set<String> videoIds) {
        if (items == null) return null;

        String continuation = null;
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;

            // The web player lists tracks as responsive items, the app as two column items.
            JSONObject track = item.optJSONObject("musicResponsiveListItemRenderer");
            if (track == null) track = item.optJSONObject("musicTwoColumnItemRenderer");
            if (track != null) {
                if (GREYED_OUT_POLICY.equals(track.optString("musicItemRendererDisplayPolicy"))) {
                    continue;
                }
                String videoId = trackVideoId(track);
                if (videoId != null) videoIds.add(videoId);
                continue;
            }

            JSONObject next = item.optJSONObject("continuationItemRenderer");
            if (next != null) {
                JSONObject command = findObject(next, "continuationCommand");
                if (command != null && !command.optString("token").isEmpty()) {
                    continuation = command.optString("token");
                }
            }
        }
        return continuation;
    }

    @Nullable
    private static String trackVideoId(JSONObject track) {
        JSONObject itemData = track.optJSONObject("playlistItemData");
        if (itemData != null && !itemData.optString("videoId").isEmpty()) {
            return itemData.optString("videoId");
        }
        // Album tracks, and the tracks the app lists, are only identified by their play endpoint.
        JSONObject watchEndpoint = findObject(track, "watchEndpoint");
        if (watchEndpoint != null && !watchEndpoint.optString("videoId").isEmpty()) {
            return watchEndpoint.optString("videoId");
        }
        return null;
    }

    @Nullable
    private static String legacyContinuation(JSONObject shelf) {
        JSONArray continuations = shelf.optJSONArray("continuations");
        if (continuations == null || continuations.length() == 0) return null;

        JSONObject next = continuations.optJSONObject(0);
        JSONObject data = next == null ? null : next.optJSONObject("nextContinuationData");
        String token = data == null ? "" : data.optString("continuation");
        return token.isEmpty() ? null : token;
    }

    /**
     * @return The first object with the given key, searched depth first.
     */
    @Nullable
    private static JSONObject findObject(@Nullable Object node, String key) {
        if (node instanceof JSONObject object) {
            JSONObject match = object.optJSONObject(key);
            if (match != null) return match;

            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                JSONObject found = findObject(object.opt(keys.next()), key);
                if (found != null) return found;
            }
        } else if (node instanceof JSONArray array) {
            for (int i = 0; i < array.length(); i++) {
                JSONObject found = findObject(array.opt(i), key);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static JSONObject newRequestBody(boolean asApp) throws JSONException {
        JSONObject client = new JSONObject();
        if (asApp) {
            client.put("clientName", APP_CLIENT_NAME);
            client.put("clientVersion", Utils.getAppVersionName());
            client.put("osName", "Android");
            client.put("osVersion", Build.VERSION.RELEASE);
            client.put("androidSdkVersion", Build.VERSION.SDK_INT);
            client.put("deviceMake", Build.MANUFACTURER);
            client.put("deviceModel", Build.MODEL);
            Locale locale = Requester.getAppLocale();
            client.put("hl", locale.getLanguage());
            client.put("gl", locale.getCountry());
        } else {
            client.put("clientName", WEB_CLIENT_NAME);
            client.put("clientVersion", WEB_CLIENT_VERSION);
            client.put("hl", "en");
        }

        JSONObject context = new JSONObject();
        context.put("client", client);
        context.put("user", new JSONObject());
        return new JSONObject().put("context", context);
    }

    private static void setHeaders(HttpURLConnection connection, boolean asApp) {
        connection.setRequestProperty("Content-Type", "application/json");
        if (asApp) {
            String version = Utils.getAppVersionName();
            connection.setRequestProperty("User-Agent", "com.google.android.apps.youtube.music/"
                    + version + " (Linux; U; Android " + Build.VERSION.RELEASE + ") gzip");
            connection.setRequestProperty("X-YouTube-Client-Name", APP_CLIENT_ID);
            connection.setRequestProperty("X-YouTube-Client-Version", version);
            connection.setRequestProperty("X-GOOG-API-FORMAT-VERSION", "2");
            for (Map.Entry<String, String> header : AuthUtils.getRequestHeader().entrySet()) {
                if (!header.getValue().isEmpty()) {
                    connection.setRequestProperty(header.getKey(), header.getValue());
                }
            }
        } else {
            // The web player is never sent the token of the app, which belongs to another client.
            connection.setRequestProperty("User-Agent", WEB_USER_AGENT);
            connection.setRequestProperty("X-YouTube-Client-Name", WEB_CLIENT_ID);
            connection.setRequestProperty("X-YouTube-Client-Version", WEB_CLIENT_VERSION);
            connection.setRequestProperty("Origin", "https://music.youtube.com");
            connection.setRequestProperty("Cookie", "SOCS=CAI");
        }
    }

    @Nullable
    private static JSONObject send(JSONObject body, boolean asApp) {
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);

        for (int attempt = 1; attempt <= MAX_FETCH_ATTEMPTS; attempt++) {
            HttpURLConnection connection = null;
            try {
                connection = Requester.openConnection(asApp ? APP_BROWSE_URL : WEB_BROWSE_URL);
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(CONNECT_TIMEOUT_MILLISECONDS);
                connection.setReadTimeout(READ_TIMEOUT_MILLISECONDS);
                setHeaders(connection, asApp);
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(bytes);
                }

                final int responseCode = connection.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    return Requester.parseJSONObject(connection);
                }
                final int attemptNumber = attempt;
                Logger.printInfo(() -> "Playlist request" + (asApp ? " with the account" : "")
                        + " returned HTTP " + responseCode + " (attempt " + attemptNumber + ")");
                // The request itself is refused, so trying again cannot help.
                if (responseCode >= 400 && responseCode < 500
                        && responseCode != Requester.HTTP_STATUS_CODE_TOO_MANY_REQUESTS) {
                    return null;
                }
            } catch (Exception ex) {
                final int attemptNumber = attempt;
                Logger.printInfo(() -> "Playlist request failed (attempt " + attemptNumber + ")", ex);
            } finally {
                if (connection != null) connection.disconnect();
            }

            if (attempt == MAX_FETCH_ATTEMPTS) break;
            try {
                //noinspection BusyWait
                Thread.sleep(RETRY_BACKOFF_MILLISECONDS * attempt);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }
}
