/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3384
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.originaltitles;

import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.requests.Requester;
import app.morphe.extension.shared.requests.Route;
import app.morphe.extension.youtube.patches.utils.requests.ChannelIdRoutes;

/**
 * Fetches the original description of a video from the player endpoint without an account,
 * which always returns the description as set by the uploader.
 */
final class OriginalDescriptionRequest {

    private static final Route.CompiledRoute GET_DESCRIPTION = new Route(
            Route.Method.POST,
            "player" +
                    "?prettyPrint=false" +
                    "&fields=videoDetails.shortDescription"
    ).compile();

    /**
     * Only the descriptions of opened videos are fetched. Requests that fail
     * because of network errors are removed, so they are fetched again later.
     */
    private static final Map<String, OriginalDescriptionRequest> cache =
            Collections.synchronizedMap(Utils.createSizeRestrictedMap(50));

    private final Future<String> future;

    private OriginalDescriptionRequest(String videoId) {
        this.future = Utils.submitOnBackgroundThread(() -> fetch(videoId));
    }

    static OriginalDescriptionRequest fetchRequestIfNeeded(String videoId) {
        return cache.computeIfAbsent(videoId, OriginalDescriptionRequest::new);
    }

    /**
     * Does not wait for the fetch.
     *
     * @return The original description, or null if not yet fetched or the video has no description.
     */
    @Nullable
    String getDescriptionIfFetched() {
        if (!future.isDone()) {
            return null;
        }
        try {
            return future.get();
        } catch (ExecutionException ex) {
            Logger.printException(() -> "getDescriptionIfFetched failure", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        return null;
    }

    @Nullable
    private static String fetch(String videoId) {
        Utils.verifyOffMainThread();

        try {
            byte[] requestBody = ChannelIdRoutes.createBody(videoId);
            HttpURLConnection connection = ChannelIdRoutes.getConnection(GET_DESCRIPTION);
            connection.setFixedLengthStreamingMode(requestBody.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(requestBody);
            }

            final int responseCode = connection.getResponseCode();
            if (responseCode == Requester.HTTP_STATUS_CODE_SUCCESS) {
                JSONObject videoDetails = Requester.parseJSONObject(connection)
                        .optJSONObject("videoDetails");
                String description = videoDetails == null ? "" : videoDetails.optString("shortDescription");
                return description.isEmpty() ? null : description;
            }

            Logger.printDebug(() -> "Description request failed for: " + videoId + " code: " + responseCode);
        } catch (IOException ex) {
            Logger.printInfo(() -> "Could not fetch original description of: " + videoId, ex);
            cache.remove(videoId);
        } catch (Exception ex) {
            Logger.printException(() -> "fetch failure", ex);
        }
        return null;
    }
}
