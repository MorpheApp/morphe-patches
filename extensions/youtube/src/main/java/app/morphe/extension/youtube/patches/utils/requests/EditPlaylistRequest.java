/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches.utils.requests;

import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.util.Collections;
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

public class EditPlaylistRequest {
    private static final int MAX_MILLISECONDS_TO_WAIT_FOR_FETCH = 20 * 1000;

    public static final Map<String, EditPlaylistRequest> cache = Collections.synchronizedMap(
            Utils.createSizeRestrictedMap(50));

    private final Future<String> future;

    private EditPlaylistRequest(
            String videoId,
            String playlistId,
            @Nullable String setVideoId,
            Map<String, String> requestHeader
    ) {
        this.future = Utils.submitOnBackgroundThread(() -> fetch(videoId, playlistId, setVideoId, requestHeader));
    }

    @Nullable
    public String getResult() {
        try {
            if (BaseSettings.DEBUG.get() && !future.isDone() && Utils.isCurrentlyOnMainThread()) {
                Logger.printException(() -> "Debug: Blocking main thread");
            }
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

    public static void clear() {
        cache.clear();
    }

    public static void clearVideoId(String videoId) {
        cache.remove(videoId);
    }

    public static void fetchRequestIfNeeded(
            String videoId,
            String playlistId,
            @Nullable String setVideoId,
            Map<String, String> requestHeader
    ) {
        cache.computeIfAbsent(
                Objects.requireNonNull(videoId),
                k -> new EditPlaylistRequest(k, playlistId, setVideoId, requestHeader)
        );
    }

    @Nullable
    public static EditPlaylistRequest getRequestForVideoId(String videoId) {
        return cache.get(videoId);
    }

    private static void handleConnectionError(String toastMessage, @Nullable Exception ex) {
        Logger.printInfo(() -> toastMessage, ex);
    }

    @Nullable
    private static JSONObject sendRequest(
            String videoId,
            String playlistId,
            @Nullable String setVideoId,
            Map<String, String> requestHeader
    ) {
        Objects.requireNonNull(videoId);
        Utils.verifyOffMainThread();

        final long startTime = System.currentTimeMillis();
        Logger.printDebug(() -> "Fetching edit playlist request, videoId: " + videoId +
                ", playlistId: " + playlistId + ", setVideoId: " + setVideoId);

        try {
            byte[] requestBody = PlaylistRoutes.editPlaylistBody(videoId, playlistId, setVideoId);
            HttpURLConnection connection = PlaylistRoutes.getConnection(PlaylistRoutes.EDIT_PLAYLIST, requestHeader);
            connection.setFixedLengthStreamingMode(requestBody.length);
            connection.getOutputStream().write(requestBody);
            int responseCode = connection.getResponseCode();
            if (responseCode == 200) {
                return Requester.parseJSONObject(connection);
            }
            handleConnectionError("Edit playlist failed with code: " + responseCode, null);
        } catch (SocketTimeoutException ex) {
            handleConnectionError("Connection timeout", ex);
        } catch (IOException ex) {
            handleConnectionError("Network error", ex);
        } catch (Exception ex) {
            Logger.printException(() -> "sendRequest failed", ex);
        } finally {
            Logger.printDebug(() -> "video: " + videoId + " took: " + (System.currentTimeMillis() - startTime) + "ms");
        }
        return null;
    }

    @Nullable
    private static String parseResponse(JSONObject json, boolean remove) {
        try {
            if ("STATUS_SUCCEEDED".equals(json.getString("status"))) {
                if (remove) {
                    return "";
                }
                Object playlistEditResultsElement = json.getJSONArray("playlistEditResults").get(0);
                if (playlistEditResultsElement instanceof JSONObject elementJson) {
                    return elementJson
                            .getJSONObject("playlistEditVideoAddedResultData")
                            .getString("setVideoId");
                }
            }
        } catch (JSONException e) {
            Logger.printException(() -> "parseResponse failed: " + json, e);
        }
        return null;
    }

    @Nullable
    private static String fetch(
            String videoId,
            String playlistId,
            @Nullable String setVideoId,
            Map<String, String> requestHeader
    ) {
        JSONObject json = sendRequest(videoId, playlistId, setVideoId, requestHeader);
        if (json != null) {
            return parseResponse(json, setVideoId != null && !setVideoId.isEmpty());
        }
        return null;
    }
}
