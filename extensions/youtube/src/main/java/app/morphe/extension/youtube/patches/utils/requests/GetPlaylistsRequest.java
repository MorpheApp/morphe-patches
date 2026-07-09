/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches.utils.requests;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.requests.Requester;
import app.morphe.extension.shared.settings.BaseSettings;
import kotlin.Pair;

public class GetPlaylistsRequest {
    private static final int MAX_MILLISECONDS_TO_WAIT_FOR_FETCH = 20 * 1000;

    public static final Map<String, GetPlaylistsRequest> cache = Collections.synchronizedMap(
            Utils.createSizeRestrictedMap(50));

    private final Future<Pair<String, String>[]> future;

    private GetPlaylistsRequest(String playlistId, Map<String, String> requestHeader) {
        this.future = Utils.submitOnBackgroundThread(() -> fetch(playlistId, requestHeader));
    }

    @Nullable
    public Pair<String, String>[] getPlaylists() {
        try {
            if (BaseSettings.DEBUG.get() && !future.isDone() && Utils.isCurrentlyOnMainThread()) {
                Logger.printException(() -> "Debug: Blocking main thread");
            }
            return future.get(MAX_MILLISECONDS_TO_WAIT_FOR_FETCH, TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            Logger.printInfo(() -> "getPlaylists timed out", ex);
        } catch (InterruptedException ex) {
            Logger.printException(() -> "getPlaylists interrupted", ex);
            Thread.currentThread().interrupt();
        } catch (ExecutionException ex) {
            Logger.printException(() -> "getPlaylists failure", ex);
        }
        return null;
    }

    public static void clear() {
        cache.clear();
    }

    public static GetPlaylistsRequest fetchRequestIfNeeded(String playlistId, Map<String, String> requestHeader) {
        cache.computeIfAbsent(
                Objects.requireNonNull(playlistId),
                k -> new GetPlaylistsRequest(playlistId, requestHeader)
        );
        return cache.get(playlistId);
    }

    @Nullable
    public static GetPlaylistsRequest getRequestForPlaylistId(String playlistId) {
        return cache.get(playlistId);
    }

    private static void handleConnectionError(String toastMessage, @Nullable Exception ex) {
        Logger.printInfo(() -> toastMessage, ex);
    }

    @Nullable
    private static JSONObject sendRequest(String playlistId, Map<String, String> requestHeader) {
        Objects.requireNonNull(playlistId);
        Utils.verifyOffMainThread();

        final long startTime = System.currentTimeMillis();
        Logger.printDebug(() -> "Fetching get playlists request, playlistId: " + playlistId);

        try {
            byte[] requestBody = PlaylistRoutes.getPlaylistsBody(playlistId);
            HttpURLConnection connection = PlaylistRoutes.getConnection(PlaylistRoutes.GET_PLAYLISTS, requestHeader);
            connection.setFixedLengthStreamingMode(requestBody.length);
            connection.getOutputStream().write(requestBody);
            int responseCode = connection.getResponseCode();
            if (responseCode == 200) {
                return Requester.parseJSONObject(connection);
            }
            handleConnectionError("Get playlists failed with code: " + responseCode, null);
        } catch (SocketTimeoutException ex) {
            handleConnectionError("Connection timeout", ex);
        } catch (IOException ex) {
            handleConnectionError("Network error", ex);
        } catch (Exception ex) {
            Logger.printException(() -> "sendRequest failed", ex);
        } finally {
            Logger.printDebug(() -> "playlist: " + playlistId + " took: " + (System.currentTimeMillis() - startTime) + "ms");
        }
        return null;
    }

    @Nullable
    @SuppressWarnings("unchecked")
    private static Pair<String, String>[] parseResponse(JSONObject json) {
        try {
            Object addToPlaylistRendererElement = json.getJSONArray("contents").get(0);
            if (addToPlaylistRendererElement instanceof JSONObject addToPlaylistRendererObj) {
                JSONArray playlistsJsonArray = addToPlaylistRendererObj
                        .getJSONObject("addToPlaylistRenderer")
                        .getJSONArray("playlists");

                int playlistsLength = playlistsJsonArray.length();
                List<Pair<String, String>> playlists = new ArrayList<>(playlistsLength);

                for (int i = 0; i < playlistsLength; i++) {
                    Object elementsElement = playlistsJsonArray.get(i);
                    if (elementsElement instanceof JSONObject elementJson) {
                        JSONObject renderer = elementJson.getJSONObject("playlistAddToOptionRenderer");
                        String id = renderer.getString("playlistId");
                        String title = ((JSONObject) renderer.getJSONObject("title")
                                .getJSONArray("runs")
                                .get(0))
                                .getString("text");
                        playlists.add(new Pair<>(id, title));
                    }
                }

                if (!playlists.isEmpty()) {
                    return playlists.toArray(new Pair[0]);
                }
            }
        } catch (JSONException e) {
            Logger.printException(() -> "parseResponse failed: " + json, e);
        }
        return null;
    }

    @Nullable
    private static Pair<String, String>[] fetch(String playlistId, Map<String, String> requestHeader) {
        JSONObject json = sendRequest(playlistId, requestHeader);
        if (json != null) {
            return parseResponse(json);
        }
        return null;
    }
}
