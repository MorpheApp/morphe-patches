/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
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

public class SavePlaylistRequest {
    private static final int MAX_MILLISECONDS_TO_WAIT_FOR_FETCH = 20 * 1000;

    public static final Map<String, SavePlaylistRequest> cache = Collections.synchronizedMap(
            Utils.createSizeRestrictedMap(50));

    private final Future<Boolean> future;

    private SavePlaylistRequest(String playlistId, String libraryId, Map<String, String> requestHeader) {
        this.future = Utils.submitOnBackgroundThread(() -> fetch(playlistId, libraryId, requestHeader));
    }

    @Nullable
    public Boolean getResult() {
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

    public static SavePlaylistRequest fetchRequestIfNeeded(
            String playlistId,
            String libraryId,
            Map<String, String> requestHeader
    ) {
        Objects.requireNonNull(playlistId);
        return cache.put(libraryId, new SavePlaylistRequest(playlistId, libraryId, requestHeader));
    }

    @Nullable
    public static SavePlaylistRequest getRequestForLibraryId(String libraryId) {
        return cache.get(libraryId);
    }

    private static void handleConnectionError(String toastMessage, @Nullable Exception ex) {
        Logger.printInfo(() -> toastMessage, ex);
    }

    @Nullable
    private static JSONObject sendRequest(
            String playlistId,
            String libraryId,
            Map<String, String> requestHeader
    ) {
        Objects.requireNonNull(playlistId);
        Objects.requireNonNull(libraryId);
        long startTime = System.currentTimeMillis();
        Logger.printDebug(() -> "Fetching save playlist request, playlistId: " + playlistId + ", libraryId: " + libraryId);
        try {
            byte[] requestBody = PlaylistRoutes.savePlaylistBody(libraryId, playlistId);
            HttpURLConnection connection = PlaylistRoutes.getConnection(PlaylistRoutes.EDIT_PLAYLIST, requestHeader);
            connection.setFixedLengthStreamingMode(requestBody.length);
            connection.getOutputStream().write(requestBody);
            int responseCode = connection.getResponseCode();
            if (responseCode == 200) {
                return Requester.parseJSONObject(connection);
            }
            handleConnectionError("Save playlist failed with code: " + responseCode, null);
        } catch (SocketTimeoutException ex) {
            handleConnectionError("Connection timeout", ex);
        } catch (IOException ex) {
            handleConnectionError("Network error", ex);
        } catch (Exception ex) {
            Logger.printException(() -> "sendRequest failed", ex);
        } finally {
            Logger.printDebug(() -> "playlistId: " + playlistId + " libraryId: " + libraryId + " took: " + (System.currentTimeMillis() - startTime) + "ms");
        }
        return null;
    }

    @Nullable
    private static Boolean parseResponse(JSONObject json) {
        try {
            return "STATUS_SUCCEEDED".equals(json.getString("status"));
        } catch (JSONException e) {
            Logger.printException(() -> "parseResponse failed: " + json, e);
        }
        return null;
    }

    @Nullable
    private static Boolean fetch(
            String playlistId,
            String libraryId,
            Map<String, String> requestHeader
    ) {
        JSONObject json = sendRequest(playlistId, libraryId, requestHeader);
        if (json != null) {
            return parseResponse(json);
        }
        return null;
    }
}
