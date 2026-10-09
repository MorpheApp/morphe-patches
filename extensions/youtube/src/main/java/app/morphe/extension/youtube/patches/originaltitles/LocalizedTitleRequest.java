/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3447
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.originaltitles;

import android.content.Context;
import android.icu.util.ULocale;
import android.os.Build;
import android.view.textclassifier.TextClassificationManager;
import android.view.textclassifier.TextLanguage;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

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
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.requests.Requester;
import app.morphe.extension.youtube.patches.utils.requests.ChannelSearchRoutes;

/**
 * Fetches the title of a video in the language of the app, as shown by YouTube.
 * Used to verify which text of an element is the translated title of the video.
 * <p>
 * Titles that are not translated by the uploader can be auto-translated in the lists, such as the
 * search results, so the title can also be fetched as shown in the lists. Elements can also show
 * the title in another language, such as a search result shown in the language of the search,
 * so the title can also be fetched in the language of a text, or as shown in the search results of a text.
 */
final class LocalizedTitleRequest {

    /**
     * Keys from the response to the title runs.
     */
    private static final String[] TITLE_PATH = {
            "playerOverlays", "playerOverlayRenderer", "videoDetails", "playerOverlayVideoDetailsRenderer", "title"
    };

    /**
     * Keys from the search response to the sections of the results.
     */
    private static final String[] SEARCH_SECTIONS_PATH = {"contents", "sectionListRenderer"};

    /**
     * Video id, language, and the search of the title shown in the lists, if any -> localized title.
     * A null title means the video has no title, or the title failed to fetch.
     * Requests that fail because of network errors or temporary errors of the server are removed,
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
        return key(videoId, Locale.getDefault(), null);
    }

    /**
     * @param searchQuery Search of the title shown in the lists, or null for the title of the video.
     */
    private static String key(String videoId, Locale locale, @Nullable String searchQuery) {
        String key = videoId + ' ' + locale.toLanguageTag();
        if (searchQuery == null) {
            return key;
        }
        return searchQuery.equals(videoId) ? key + " list" : key + " search " + searchQuery;
    }

    /**
     * Starts fetching the title in the current language, if not yet fetched.
     */
    static CompletableFuture<String> fetch(String videoId) {
        return fetch(videoId, Locale.getDefault(), null);
    }

    /**
     * @param searchQuery Search of the title shown in the lists, or null for the title of the video.
     */
    private static CompletableFuture<String> fetch(String videoId, Locale locale, @Nullable String searchQuery) {
        String key = key(videoId, locale, searchQuery);
        if (RequestBackoff.isPaused()) {
            CompletableFuture<String> request = cache.get(key);
            // Not cached, so the title is fetched again after the pause.
            return request != null ? request : CompletableFuture.completedFuture(null);
        }
        return cache.computeIfAbsent(key, k -> CompletableFuture.supplyAsync(
                () -> fetchTitle(key, videoId, locale, searchQuery), Utils::runOnBackgroundThread));
    }

    /**
     * Starts fetching the title in the current language as shown in the lists, if not yet fetched.
     *
     * @return The request of the title, or a request of a null title if the search results
     *         do not include the video. The request fails with an {@link IOException} if the title
     *         could not be fetched because of network errors, so it can be fetched again later.
     */
    static CompletableFuture<String> fetchListTitle(String videoId) {
        return fetchFailingOnNetworkError(videoId, Locale.getDefault(), videoId);
    }

    /**
     * Starts fetching the title in the current language as shown in the search results of the text,
     * if not yet fetched. The search results are shown in the language of the search, so the server
     * finds the language of a text in another language, which the device does not always detect,
     * such as on devices without the language detection of the text classifier.
     *
     * @return The request of the title, or a request of a null title if the search results
     *         do not include the video. The request fails with an {@link IOException} if the title
     *         could not be fetched because of network errors, so it can be fetched again later.
     */
    static CompletableFuture<String> fetchSearchTitle(String videoId, String text) {
        // Elements can show the title truncated, such as 'Start of the title...'.
        String query = text.replaceFirst("(\\.\\.\\.|…)$", "").trim();
        if (query.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        return fetchFailingOnNetworkError(videoId, Locale.getDefault(), query);
    }

    /**
     * Starts fetching the title in the language of the text, if the language is detected
     * and is not the language of the app. Must be called off the main thread.
     *
     * @return The request of the title, or a request of a null title if the language is not detected,
     *         is the language of the app, or the video has no title in the language.
     *         The request fails with an {@link IOException} if the title could not be fetched
     *         because of network errors, so it can be fetched again later.
     */
    static CompletableFuture<String> fetchInLanguageOf(String videoId, String text) {
        Locale locale = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                ? TextLanguageDetector.detectLanguage(text)
                : null;
        if (locale == null || locale.getLanguage().equals(Locale.getDefault().getLanguage())) {
            return CompletableFuture.completedFuture(null);
        }
        Logger.printDebug(() -> "Fetching title of: " + videoId
                + " in language of the text: " + locale.toLanguageTag());
        return fetchFailingOnNetworkError(videoId, locale, null);
    }

    /**
     * Starts fetching the title, if not yet fetched.
     *
     * @return The request of the title, which fails with an {@link IOException}
     *         if the title could not be fetched because of network errors.
     */
    private static CompletableFuture<String> fetchFailingOnNetworkError(String videoId, Locale locale,
                                                                        @Nullable String searchQuery) {
        CompletableFuture<String> request = fetch(videoId, locale, searchQuery);
        String key = key(videoId, locale, searchQuery);
        return request.thenApply(title -> {
            // Requests that fail because of network errors are removed from the cache.
            if (title == null && cache.get(key) != request) {
                throw new CompletionException(new IOException("Could not fetch title: " + key));
            }
            return title;
        });
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

    /**
     * @param searchQuery Search of the title shown in the lists, or null for the title of the video.
     */
    @SuppressWarnings("deprecation")
    @Nullable
    private static String fetchTitle(String key, String videoId, Locale locale, @Nullable String searchQuery) {
        final boolean listTitle = searchQuery != null;
        String language = locale.toLanguageTag();
        Locale requestLocale = unsupportedRegionLanguages.contains(language)
                ? new Locale(locale.getLanguage())
                : locale;
        try {
            byte[] requestBody = listTitle
                    ? ChannelSearchRoutes.createVideoSearchBody(searchQuery, requestLocale)
                    : ChannelSearchRoutes.createVideoBody(videoId, requestLocale);
            HttpURLConnection connection = ChannelSearchRoutes.getConnection(listTitle
                    ? ChannelSearchRoutes.GET_LIST_VIDEO_TITLE
                    : ChannelSearchRoutes.GET_LOCALIZED_VIDEO_TITLE);
            connection.setFixedLengthStreamingMode(requestBody.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(requestBody);
            }

            final int responseCode = connection.getResponseCode();
            if (responseCode == Requester.HTTP_STATUS_CODE_SUCCESS) {
                JSONObject json = Requester.parseJSONObject(connection);
                JSONArray runs;
                if (listTitle) {
                    runs = findListTitleRuns(json, videoId);
                } else {
                    JSONObject titleJson = json;
                    for (String pathKey : TITLE_PATH) {
                        titleJson = titleJson == null ? null : titleJson.optJSONObject(pathKey);
                    }
                    runs = titleJson == null ? null : titleJson.optJSONArray("runs");
                }
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
                return fetchTitle(key, videoId, locale, searchQuery);
            }
            if (RequestBackoff.isTemporaryError(responseCode)) {
                // Fetched again later, as the server limits the requests or is unavailable.
                RequestBackoff.onTemporaryError(connection, responseCode);
                cache.remove(key);
                return null;
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

    /**
     * The search results of a video id can include other videos, so the title is the title
     * of the result with the same video id.
     *
     * @return The runs of the title of the video, or null if the results do not include the video.
     */
    @Nullable
    private static JSONArray findListTitleRuns(JSONObject json, String videoId) {
        for (String pathKey : SEARCH_SECTIONS_PATH) {
            json = json.optJSONObject(pathKey);
            if (json == null) {
                return null;
            }
        }
        JSONArray sections = json.optJSONArray("contents");
        if (sections == null) {
            return null;
        }
        for (int i = 0, sectionCount = sections.length(); i < sectionCount; i++) {
            JSONObject section = sections.optJSONObject(i);
            JSONObject itemSection = section == null ? null : section.optJSONObject("itemSectionRenderer");
            JSONArray items = itemSection == null ? null : itemSection.optJSONArray("contents");
            if (items == null) {
                continue;
            }
            for (int j = 0, itemCount = items.length(); j < itemCount; j++) {
                JSONObject item = items.optJSONObject(j);
                JSONObject video = item == null ? null : item.optJSONObject("videoWithContextRenderer");
                if (video != null && videoId.equals(video.optString("videoId"))) {
                    JSONObject headline = video.optJSONObject("headline");
                    return headline == null ? null : headline.optJSONArray("runs");
                }
            }
        }
        return null;
    }

    /**
     * Language detection of the text classifier, which is available since Android 10.
     * The classes of Android 10 are only used by this class, so older versions do not need to
     * verify the classes that they do not have when the requests are loaded.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private static final class TextLanguageDetector {

        /**
         * The language is detected on the device. The most likely language is used even if its
         * confidence is low, such as for a short title with emojis, as the title in a language is fetched
         * only once for each video, and the text is the title only if it's the same as the fetched title.
         *
         * @return The most likely language of the text, or null if not detected.
         */
        @Nullable
        static Locale detectLanguage(String text) {
            try {
                Context context = Utils.getContext();
                TextClassificationManager manager = context == null
                        ? null
                        : context.getSystemService(TextClassificationManager.class);
                if (manager == null) {
                    return null;
                }
                TextLanguage language = manager.getTextClassifier()
                        .detectLanguage(new TextLanguage.Request.Builder(text).build());
                if (language.getLocaleHypothesisCount() == 0) {
                    return null;
                }
                ULocale locale = language.getLocale(0);
                if (locale.getLanguage().isEmpty()) {
                    return null;
                }
                // Titles are translated to the regional variants of Chinese, and not to the scripts.
                if ("zh".equals(locale.getLanguage()) && locale.getCountry().isEmpty()) {
                    return "Hant".equals(locale.getScript()) ? Locale.TAIWAN : Locale.CHINA;
                }
                return locale.toLocale();
            } catch (Exception ex) {
                Logger.printException(() -> "detectLanguage failure", ex);
            }
            return null;
        }
    }
}
