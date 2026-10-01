/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3447
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.originaltitles;

import androidx.annotation.Nullable;

import org.json.JSONArray;
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
 * Fetches the title of a video in the language of the app, as translated by the uploader.
 * Used to confirm that a text is the translated title of a video, if other elements
 * showed the original title of the video, such as a search shelf.
 */
final class LocalizedTitleRequest {

    /**
     * Keys from the response to the title runs.
     */
    private static final String[] TITLE_PATH = {
            "playerOverlays", "playerOverlayRenderer", "videoDetails", "playerOverlayVideoDetailsRenderer", "title"
    };

    /**
     * Video id and language -> localized title. A null title means the video has no title,
     * or the title failed to fetch. Requests that fail because of network errors are removed,
     * so they are fetched again later.
     */
    private static final Map<String, CompletableFuture<String>> cache =
            Collections.synchronizedMap(Utils.createSizeRestrictedMap(200));

    private LocalizedTitleRequest() {
    }

    /**
     * Starts fetching the title in the current language, if not yet fetched.
     */
    static CompletableFuture<String> fetch(String videoId) {
        Locale locale = Locale.getDefault();
        String key = videoId + ' ' + locale.toLanguageTag();
        return cache.computeIfAbsent(key, k -> CompletableFuture.supplyAsync(
                () -> fetchTitle(key, videoId, locale), Utils::runOnBackgroundThread));
    }

    @Nullable
    private static String fetchTitle(String key, String videoId, Locale locale) {
        try {
            byte[] requestBody = ChannelSearchRoutes.createVideoBody(videoId, locale);
            HttpURLConnection connection = ChannelSearchRoutes.getConnection(
                    ChannelSearchRoutes.GET_LOCALIZED_VIDEO_TITLE);
            connection.setFixedLengthStreamingMode(requestBody.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(requestBody);
            }

            final int responseCode = connection.getResponseCode();
            if (responseCode == Requester.HTTP_STATUS_CODE_SUCCESS) {
                JSONObject json = Requester.parseJSONObject(connection);
                for (String pathKey : TITLE_PATH) {
                    json = json.optJSONObject(pathKey);
                    if (json == null) {
                        return null;
                    }
                }
                JSONArray runs = json.optJSONArray("runs");
                if (runs == null) {
                    return null;
                }
                StringBuilder title = new StringBuilder();
                for (int i = 0, length = runs.length(); i < length; i++) {
                    JSONObject run = runs.optJSONObject(i);
                    if (run != null) {
                        title.append(run.optString("text"));
                    }
                }
                String trimmed = title.toString().trim();
                return trimmed.isEmpty() ? null : trimmed;
            }

            Logger.printDebug(() -> "Localized title request failed for: " + videoId
                    + " code: " + responseCode);
        } catch (IOException ex) {
            Logger.printInfo(() -> "Could not fetch localized title of: " + videoId, ex);
            cache.remove(key);
        } catch (Exception ex) {
            Logger.printException(() -> "fetchTitle failure", ex);
        }
        return null;
    }
}
