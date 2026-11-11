/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches.utils.requests;

import android.util.Pair;

import org.jetbrains.annotations.Nullable;
import org.json.JSONObject;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.requests.Requester;
import app.morphe.extension.shared.settings.BaseSettings;

@SuppressWarnings("unused")
public class ChannelIdRequest {
    // Must be less than 5 seconds to ensure Android "App not responding" dialog does not show.
    private static final int MAX_MILLISECONDS_TO_WAIT_FOR_FETCH = 3 * 1000;
    private final String videoId;
    private final Future<Pair<String, String>> future;
    private volatile String videoTitle = "";
    @Nullable
    private volatile HttpURLConnection openConnection;

    private static final Map<String, ChannelIdRequest> cache = Collections.synchronizedMap(
            Utils.createSizeRestrictedMap(50));

    private ChannelIdRequest(String videoId) {
        this.videoId = videoId;
        this.future = Utils.submitOnBackgroundThread(() -> fetch(videoId));
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public boolean fetchIsDone() {
        return future.isDone();
    }

    /**
     * @return The pending fetch, to wait for it off the main thread.
     */
    public Future<Pair<String, String>> getFuture() {
        return future;
    }

    /**
     * Stops the fetch if it is not done yet, such as when nothing is waiting for it anymore.
     * The next request of the video fetches it again.
     */
    public void cancelIfNotDone() {
        if (future.isDone()) {
            return;
        }

        cache.remove(videoId, this);
        future.cancel(true);
        HttpURLConnection connection = openConnection;
        if (connection != null) {
            // Closing the connection also stops a response that is still being read.
            Utils.runOnBackgroundThread(connection::disconnect);
        }
    }

    @Nullable
    public Pair<String, String> getChannelInfo() {
        try {
            if (BaseSettings.DEBUG.get() && !fetchIsDone() && Utils.isCurrentlyOnMainThread()) {
                Logger.printDebug(() -> "Debug: Blocking main thread");
            }
            return future.get(MAX_MILLISECONDS_TO_WAIT_FOR_FETCH, TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            Logger.printInfo(() -> "getChannelId timed out", ex);
        } catch (InterruptedException ex) {
            Logger.printException(() -> "getChannelId interrupted", ex);
            Thread.currentThread().interrupt();
        } catch (ExecutionException ex) {
            Logger.printException(() -> "getChannelId failure", ex);
        } catch (CancellationException ex) {
            Logger.printDebug(() -> "getChannelId cancelled");
        }
        return null;
    }

    public static ChannelIdRequest fetchRequestIfNeeded(String videoId) {
        return cache.computeIfAbsent(
                videoId,
                ChannelIdRequest::new
        );
    }

    /** Reuses an existing response without starting a request or waiting for it. */
    public static String getVideoTitleIfAvailable(String videoId) {
        ChannelIdRequest request = cache.get(videoId);
        return request == null ? "" : request.videoTitle;
    }

    private static void handleConnectionError(String toastMessage, @Nullable Exception ex) {
        Logger.printInfo(() -> toastMessage, ex);
    }

    @Nullable
    private JSONObject sendRequest(String videoId) {
        Utils.verifyOffMainThread();

        final long startTime = System.currentTimeMillis();
        Logger.printDebug(() -> "Fetching config request");

        try {
            byte[] requestBody = ChannelIdRoutes.createBody(videoId);
            HttpURLConnection connection = ChannelIdRoutes.getConnection(ChannelIdRoutes.GET_CHANNEL_ID);
            openConnection = connection;
            // The fetch can start before the constructor sets the future.
            if (future != null && future.isCancelled()) {
                return null; // Cancelled before the request was sent.
            }
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
    private Pair<String, String> fetch(String videoId) {
        JSONObject json = sendRequest(videoId);
        JSONObject videoDetails = json == null ? null : json.optJSONObject("videoDetails");
        if (videoDetails != null) {
            if (videoId.equals(videoDetails.optString("videoId"))) {
                videoTitle = videoDetails.optString("title");
            }
            return new Pair<>(
                    videoDetails.optString("author", ""),
                    videoDetails.optString("channelId", "")
            );
        }
        return null;
    }
}
