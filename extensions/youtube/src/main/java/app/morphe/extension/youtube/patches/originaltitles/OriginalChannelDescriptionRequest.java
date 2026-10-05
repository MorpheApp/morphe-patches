/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3418
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
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.requests.Requester;
import app.morphe.extension.youtube.patches.utils.requests.ChannelSearchRoutes;

/**
 * Fetches the original description of a channel from the browse endpoint without an account,
 * which always returns the description as set by the uploader.
 */
final class OriginalChannelDescriptionRequest {

    /**
     * Keys from the response to the profile page, which has the description
     * in the default language of the channel.
     */
    private static final String[] PROFILE_PAGE_PATH = {
            "microformat", "microformatDataRenderer", "channelProfileMicroformatDetails", "profilePage"
    };

    /**
     * Channel id -> original description. A null description means the channel has no description.
     * Requests that fail because of network errors are removed, so they are fetched again later.
     */
    private static final Map<String, CompletableFuture<String>> cache =
            Collections.synchronizedMap(Utils.createSizeRestrictedMap(50));

    private OriginalChannelDescriptionRequest() {
    }

    static CompletableFuture<String> fetch(String channelId) {
        return cache.computeIfAbsent(channelId, key -> CompletableFuture.supplyAsync(
                () -> fetchDescription(key), Utils::runOnBackgroundThread));
    }

    /**
     * Starts fetching the description if needed, and does not wait for it.
     *
     * @return The original description, or null if not yet fetched or the channel has no description.
     */
    @Nullable
    static String getIfAvailable(String channelId) {
        return fetch(channelId).getNow(null);
    }

    /**
     * @return If the description is being fetched.
     */
    static boolean isPending(String channelId) {
        CompletableFuture<String> future = cache.get(channelId);
        return future != null && !future.isDone();
    }

    @SuppressWarnings("deprecation")
    @Nullable
    private static String fetchDescription(String channelId) {
        try {
            // The original description does not depend on the country, and the endpoint
            // rejects some countries, such as CN, so the default country is used.
            byte[] requestBody = ChannelSearchRoutes.createChannelBody(channelId,
                    new Locale(Locale.getDefault().getLanguage()));
            HttpURLConnection connection = ChannelSearchRoutes.getConnection(
                    ChannelSearchRoutes.GET_CHANNEL_DESCRIPTION);
            connection.setFixedLengthStreamingMode(requestBody.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(requestBody);
            }

            final int responseCode = connection.getResponseCode();
            if (responseCode == Requester.HTTP_STATUS_CODE_SUCCESS) {
                JSONObject json = Requester.parseJSONObject(connection);
                String description = "";
                for (String key : PROFILE_PAGE_PATH) {
                    json = json.optJSONObject(key);
                    if (json == null) {
                        break;
                    }
                }
                if (json != null) {
                    description = json.optString("description").trim();
                }
                return description.isEmpty() ? null : description;
            }

            Logger.printDebug(() -> "Channel description request failed for: " + channelId
                    + " code: " + responseCode);
        } catch (IOException ex) {
            Logger.printInfo(() -> "Could not fetch original description of channel: " + channelId, ex);
            cache.remove(channelId);
        } catch (Exception ex) {
            Logger.printException(() -> "fetchDescription failure", ex);
        }
        return null;
    }
}
