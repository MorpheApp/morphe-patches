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
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.requests.Requester;
import app.morphe.extension.youtube.patches.utils.requests.ChannelSearchRoutes;

/**
 * Fetches the title of a video in the language of the app, as shown by YouTube.
 * Used to verify which text of an element is the translated title of the video.
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
            Collections.synchronizedMap(Utils.createSizeRestrictedMap(1000));

    /**
     * HTTP status of a request with a language that the server does not support.
     */
    private static final int HTTP_STATUS_CODE_BAD_REQUEST = 400;

    /**
     * Languages of the app whose regional variant the server does not support, such as 'ar-SA',
     * so the titles are fetched in the language without the region, such as 'ar'.
     */
    private static final Set<String> unsupportedRegionLanguages = ConcurrentHashMap.newKeySet();

    private LocalizedTitleRequest() {
    }

    private static String key(String videoId) {
        return videoId + ' ' + Locale.getDefault().toLanguageTag();
    }

    /**
     * Starts fetching the title in the current language, if not yet fetched.
     */
    static CompletableFuture<String> fetch(String videoId) {
        Locale locale = Locale.getDefault();
        String key = key(videoId);
        return cache.computeIfAbsent(key, k -> CompletableFuture.supplyAsync(
                () -> fetchTitle(key, videoId, locale), Utils::runOnBackgroundThread));
    }

    /**
     * Does not start fetching the title.
     *
     * @return The request of the title in the current language, or null if not started.
     */
    @Nullable
    static CompletableFuture<String> getRequest(String videoId) {
        return cache.get(key(videoId));
    }

    /**
     * @return If the title in the current language is being fetched.
     */
    static boolean isPending(String videoId) {
        CompletableFuture<String> request = getRequest(videoId);
        return request != null && !request.isDone();
    }

    @Nullable
    private static String fetchTitle(String key, String videoId, Locale locale) {
        String language = locale.toLanguageTag();
        Locale requestLocale = unsupportedRegionLanguages.contains(language)
                ? new Locale(locale.getLanguage())
                : locale;
        try {
            byte[] requestBody = ChannelSearchRoutes.createVideoBody(videoId, requestLocale);
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

            if (responseCode == HTTP_STATUS_CODE_BAD_REQUEST && !requestLocale.getCountry().isEmpty()) {
                Logger.printDebug(() -> "Language not supported: " + language + ", using: " + locale.getLanguage());
                unsupportedRegionLanguages.add(language);
                return fetchTitle(key, videoId, locale);
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
