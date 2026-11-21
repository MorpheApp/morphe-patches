/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches.utils.requests;

import androidx.annotation.NonNull;
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
import app.morphe.extension.youtube.patches.SaveToWatchLaterPatch;

public class SaveToWatchLaterRequest {
    private final Future<Boolean> future;

    private SaveToWatchLaterRequest(
            String videoId,
            Map<String, String> requestHeader
    ) {
        this.future = Utils.submitOnBackgroundThread(() -> fetch(videoId, requestHeader));
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public boolean fetchIsDone() {
        return future.isDone();
    }

    @Nullable
    public Boolean getResult() {
        return PlaylistRoutes.getResult(future, "getResult");
    }

    public static SaveToWatchLaterRequest fetchRequest(
            @NonNull String videoId,
            @NonNull Map<String, String> requestHeader
    ) {
        return new SaveToWatchLaterRequest(videoId, requestHeader);
    }

    @Nullable
    private static JSONObject sendRequest(
            String videoId,
            Map<String, String> requestHeader
    ) {
        Objects.requireNonNull(videoId);
        Utils.verifyOffMainThread();

        final long startTime = System.currentTimeMillis();
        Logger.printDebug(() -> "Fetching save to playlist request, videoId: " + videoId);

        try {
            byte[] requestBody = PlaylistRoutes.editPlaylistBody(
                    videoId, SaveToWatchLaterPatch.getWatchLaterPlaylistId(), null);
            HttpURLConnection connection = PlaylistRoutes.getConnection(PlaylistRoutes.EDIT_PLAYLIST, requestHeader);
            connection.setFixedLengthStreamingMode(requestBody.length);
            connection.getOutputStream().write(requestBody);
            int responseCode = connection.getResponseCode();
            if (responseCode == Requester.HTTP_STATUS_CODE_SUCCESS) {
                return Requester.parseJSONObject(connection);
            }
            PlaylistRoutes.handleConnectionError("Save to Watch later failed with code: " + responseCode, null);
        } catch (SocketTimeoutException ex) {
            PlaylistRoutes.handleConnectionError("Connection timeout", ex);
        } catch (IOException ex) {
            PlaylistRoutes.handleConnectionError("Network error", ex);
        } catch (Exception ex) {
            Logger.printException(() -> "sendRequest failed", ex);
        } finally {
            Logger.printDebug(() -> "video: " + videoId + " took: " + (System.currentTimeMillis() - startTime) + "ms");
        }
        return null;
    }

    @Nullable
    private static Boolean parseResponse(JSONObject json) {
        try {
            if ("STATUS_SUCCEEDED".equals(json.getString("status"))) {
                return json.has("playlistEditResults");
            }
        } catch (JSONException e) {
            Logger.printException(() -> "parseResponse failed: " + json, e);
        }
        return null;
    }

    @Nullable
    private static Boolean fetch(
            String videoId,
            Map<String, String> requestHeader
    ) {
        JSONObject json = sendRequest(videoId, requestHeader);
        if (json != null) {
            return parseResponse(json);
        }
        return null;
    }
}
