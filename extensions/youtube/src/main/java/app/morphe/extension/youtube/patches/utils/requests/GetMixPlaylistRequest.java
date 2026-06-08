/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.utils.requests;

import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.requests.Requester;

public class GetMixPlaylistRequest {
    private static final long MAX_MILLISECONDS_TO_WAIT_FOR_FETCH = 20 * 1000; // 20 seconds

    private static final Map<String, GetMixPlaylistRequest> cache =
            Utils.createSizeRestrictedMap(30);

    private final Future<Boolean> future;

    private GetMixPlaylistRequest(String videoId, Map<String, String> requestHeader) {
        this.future = Utils.submitOnBackgroundThread(() -> fetch(videoId, requestHeader));
    }

    public Boolean getResult() {
        try {
            return future.get(MAX_MILLISECONDS_TO_WAIT_FOR_FETCH, TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            Logger.printInfo(() -> "getResult timed out", ex);
        } catch (InterruptedException ex) {
            Logger.printException(() -> "getResult interrupted", ex);
            Thread.currentThread().interrupt();
        } catch (ExecutionException ex) {
            Logger.printException(() -> "getResult failure", ex);
        }

        return null;
    }

    public static void fetchRequestIfNeeded(String videoId, Map<String, String> requestHeader) {
        cache.computeIfAbsent(
                Objects.requireNonNull(videoId),
                k -> new GetMixPlaylistRequest(videoId, requestHeader)
        );
    }

    @Nullable
    public static GetMixPlaylistRequest getRequestForVideoId(String videoId) {
        return cache.get(videoId);
    }

    private static void handleConnectionError(String toastMessage, @Nullable Exception ex) {
        Logger.printInfo(() -> toastMessage, ex);
    }

    @Nullable
    private static JSONObject sendRequest(String videoId, Map<String, String> requestHeader) {
        Objects.requireNonNull(videoId);

        final long startTime = System.currentTimeMillis();
        Logger.printDebug(() -> "Fetching get mix playlist, videoId: " + videoId);

        try {
            byte[] requestBody = PlaylistRoutes.getMixPlaylistBody(videoId);
            HttpURLConnection connection = PlaylistRoutes.getConnection(PlaylistRoutes.GET_MIX_PLAYLIST, requestHeader);
            connection.setFixedLengthStreamingMode(requestBody.length);
            connection.getOutputStream().write(requestBody);
            int responseCode = connection.getResponseCode();
            if (responseCode == 200) {
                return Requester.parseJSONObject(connection);
            }
            handleConnectionError("Get mix playlist failed with code: " + responseCode, null);
        } catch (SocketTimeoutException ex) {
            handleConnectionError("Connection timeout", ex);
        } catch (IOException ex) {
            handleConnectionError("Network error", ex);
        } catch (Exception ex) {
            Logger.printException(() -> "send failed", ex);
        } finally {
            Logger.printDebug(() -> "mix playlist items fetch took: " + (System.currentTimeMillis() - startTime) + "ms");
        }

        return null;
    }

    private static Boolean parseResponse(JSONObject json) {
        try {
            JSONObject singleColumnWatchNextResults = json.getJSONObject("contents")
                    .getJSONObject("singleColumnWatchNextResults");
            if (!singleColumnWatchNextResults.has("playlist")) {
                return false;
            }
            JSONObject playlist = singleColumnWatchNextResults.getJSONObject("playlist")
                    .getJSONObject("playlist");
            if (!(playlist.getJSONArray("contents").get(0) instanceof JSONObject firstPlaylistContent)) {
                return false;
            }
            JSONObject navigationEndpoint = firstPlaylistContent.getJSONObject("playlistPanelVideoRenderer")
                    .getJSONObject("navigationEndpoint");
            if (!navigationEndpoint.has("coWatchWatchEndpointWrapperCommand")) {
                return false;
            }
            JSONObject watchEndpoint = navigationEndpoint.getJSONObject("coWatchWatchEndpointWrapperCommand")
                    .getJSONObject("watchEndpoint")
                    .getJSONObject("watchEndpoint");

            if (!watchEndpoint.has("playerParams")) {
                return false;
            }

            return watchEndpoint.getString("playerParams").startsWith("8AUB");
        } catch (JSONException e) {
            Logger.printDebug(() -> "parseResponse failed: " + json, e);
        }

        return false;
    }

    private static Boolean fetch(String videoId, Map<String, String> requestHeader) {
        JSONObject json = sendRequest(videoId, requestHeader);
        if (json != null) {
            return parseResponse(json);
        }
        return false;
    }
}
