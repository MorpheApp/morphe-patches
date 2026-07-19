/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches.utils.requests;

import androidx.annotation.NonNull;

import org.jetbrains.annotations.Nullable;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import app.morphe.extension.shared.requests.Requester;
import app.morphe.extension.shared.settings.BaseSettings;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

@SuppressWarnings("unused")
public class ChannelIdRequest {
    private static final int MAX_MILLISECONDS_TO_WAIT_FOR_FETCH = 5 * 1000;
    private final Future<String> future;

    private static final Map<String, ChannelIdRequest> cache = Collections.synchronizedMap(
            Utils.createSizeRestrictedMap(50));

    private ChannelIdRequest(String videoId) {
        this.future = Utils.submitOnBackgroundThread(() -> fetch(videoId));
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public boolean fetchIsDone() {
        return future.isDone();
    }

    @Nullable
    public String getChannelId() {
        try {
            if (BaseSettings.DEBUG.get() && !fetchIsDone() && Utils.isCurrentlyOnMainThread()) {
                Logger.printException(() -> "Debug: Blocking main thread");
            }
            return future.get(MAX_MILLISECONDS_TO_WAIT_FOR_FETCH, TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            Logger.printInfo(() -> "getChannelId timed out", ex);
        } catch (InterruptedException ex) {
            Logger.printException(() -> "getChannelId interrupted", ex);
            Thread.currentThread().interrupt();
        } catch (ExecutionException ex) {
            Logger.printException(() -> "getChannelId failure", ex);
        }
        return null;
    }

    public static ChannelIdRequest fetchRequestIfNeeded(@NonNull String videoId) {
        return cache.computeIfAbsent(
                videoId,
                ChannelIdRequest::new
        );
    }

    private static void handleConnectionError(String toastMessage, @Nullable Exception ex) {
        Logger.printInfo(() -> toastMessage, ex);
    }

    @Nullable
    private static String parseResponse(JSONObject json) {
        try {
            return json.getJSONObject("videoDetails").getString("channelId");
        } catch (JSONException e) {
            Logger.printDebug(() -> "parseResponse failed: " + json, e);
        }

        return null;
    }

    @Nullable
    private static JSONObject sendRequest(String videoId) {
        Utils.verifyOffMainThread();

        final long startTime = System.currentTimeMillis();
        Logger.printDebug(() -> "Fetching config request");

        try {
            byte[] requestBody = ChannelIdRoutes.createBody(videoId);
            HttpURLConnection connection = ChannelIdRoutes.getConnection(ChannelIdRoutes.GET_CHANNEL_ID);
            connection.setFixedLengthStreamingMode(requestBody.length);
            connection.getOutputStream().write(requestBody);
            int responseCode = connection.getResponseCode();
            if (responseCode == 200 && connection.getContentLength() != 0) {
                return Requester.parseJSONObject(connection);
            }
            handleConnectionError("Channel id request failed with code: " + responseCode, null);
        } catch (SocketTimeoutException ex) {
            handleConnectionError("Connection timeout", ex);
        } catch (IOException ex) {
            handleConnectionError("Network error", ex);
        } catch (Exception ex) {
            Logger.printException(() -> "sendRequest failed", ex);
        } finally {
            Logger.printDebug(() -> "Fetched channel id request, took: " + (System.currentTimeMillis() - startTime) + "ms");
        }
        return null;
    }

    @Nullable
    private static String fetch(String videoId) {
        JSONObject json = sendRequest(videoId);
        if (json != null) {
            return parseResponse(json);
        }
        return null;
    }
}
