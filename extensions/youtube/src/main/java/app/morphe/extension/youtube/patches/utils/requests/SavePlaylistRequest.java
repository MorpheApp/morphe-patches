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
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Future;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.requests.Requester;

public class SavePlaylistRequest {
    private final Future<Boolean> future;

    private SavePlaylistRequest(String playlistId, String libraryId, Map<String, String> requestHeader) {
        this.future = Utils.submitOnBackgroundThread(() -> fetch(playlistId, libraryId, requestHeader));
    }

    @Nullable
    public Boolean getResult() {
        return PlaylistRoutes.getResult(future, "getResult");
    }

    public static SavePlaylistRequest fetchRequestIfNeeded(
            String playlistId,
            String libraryId,
            Map<String, String> requestHeader
    ) {
        Objects.requireNonNull(playlistId);
        return new SavePlaylistRequest(playlistId, libraryId, requestHeader);
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
            if (responseCode == Requester.HTTP_STATUS_CODE_SUCCESS) {
                return Requester.parseJSONObject(connection);
            }
            PlaylistRoutes.handleConnectionError("Save playlist failed with code: " + responseCode, null);
        } catch (SocketTimeoutException ex) {
            PlaylistRoutes.handleConnectionError("Connection timeout", ex);
        } catch (IOException ex) {
            PlaylistRoutes.handleConnectionError("Network error", ex);
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
