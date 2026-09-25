/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2964
 * https://github.com/MorpheApp/morphe-patches/pull/3298
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches.utils.requests;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.requests.Requester;
import app.morphe.extension.youtube.settings.Settings;

public final class ChannelSearchRequest {

    public static final Pattern PATTERN_PUBLISHED_TIME = Pattern.compile("(\\d+)\\s*(year|month|week|day|hour|minute|second)");

    public static final class ChannelSearchResult {
        public final String videoId;
        public final String title;
        public final String metadata;
        public final String thumbnailUrl;
        public final long publishedTimeSeconds;
        public final long viewCount;
        public final int originalIndex;

        private ChannelSearchResult(String videoId, String title, String metadata, String thumbnailUrl,
                                    long publishedTimeSeconds, long viewCount, int originalIndex) {
            this.videoId = videoId;
            this.title = title;
            this.metadata = metadata;
            this.thumbnailUrl = thumbnailUrl;
            this.publishedTimeSeconds = publishedTimeSeconds;
            this.viewCount = viewCount;
            this.originalIndex = originalIndex;
        }
    }

    public static final class ChannelSearchResponse {
        /** Empty if the response does not name the channel. */
        public final String channelName;
        public final List<ChannelSearchResult> results;

        private ChannelSearchResponse(String channelName, List<ChannelSearchResult> results) {
            this.channelName = channelName;
            this.results = results;
        }
    }

    private static final int MAX_MILLISECONDS_TO_WAIT_FOR_FETCH = 15 * 1000;

    private static final Map<String, ChannelSearchRequest> cache = Collections.synchronizedMap(
            Utils.createSizeRestrictedMap(10));

    private final Future<ChannelSearchResponse> future;

    private ChannelSearchRequest(String channelId, String query) {
        this.future = Utils.submitOnBackgroundThread(() -> fetch(channelId, query));
    }

    public static ChannelSearchRequest fetchRequestIfNeeded(String channelId, String query) {
        return cache.computeIfAbsent(
                channelId + "\n" + query,
                key -> new ChannelSearchRequest(channelId, query)
        );
    }

    /**
     * Null if the request failed. Results are empty if the channel has nothing matching the query.
     */
    @Nullable
    public ChannelSearchResponse getResponse() {
        try {
            return future.get(MAX_MILLISECONDS_TO_WAIT_FOR_FETCH, TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            Logger.printInfo(() -> "getResponse timed out", ex);
        } catch (InterruptedException ex) {
            Logger.printException(() -> "getResponse interrupted", ex);
            Thread.currentThread().interrupt();
        } catch (ExecutionException ex) {
            Logger.printException(() -> "getResponse failure", ex);
        }
        return null;
    }

    @Nullable
    private static ChannelSearchResponse fetch(String channelId, String query) {
        Utils.verifyOffMainThread();

        Locale appLocale = Requester.getAppLocale();
        final boolean isEnglish = appLocale.getLanguage().isEmpty() || "en".equalsIgnoreCase(appLocale.getLanguage());

        if (isEnglish) {
            return fetchSingle(channelId, query, Locale.US);
        }

        Future<ChannelSearchResponse> localizedFuture = Utils.submitOnBackgroundThread(
                () -> fetchSingle(channelId, query, appLocale));
        Future<ChannelSearchResponse> englishFuture = Utils.submitOnBackgroundThread(
                () -> fetchSingle(channelId, query, Locale.US));

        try {
            ChannelSearchResponse localized = localizedFuture.get(MAX_MILLISECONDS_TO_WAIT_FOR_FETCH, TimeUnit.MILLISECONDS);
            ChannelSearchResponse english = englishFuture.get(MAX_MILLISECONDS_TO_WAIT_FOR_FETCH, TimeUnit.MILLISECONDS);

            if (localized == null) {
                return english;
            }
            if (english == null) {
                return localized;
            }

            //noinspection ExtractMethodRecommender
            Map<String, Long> englishTimes = new HashMap<>(2 * english.results.size());
            for (ChannelSearchResult item : english.results) {
                englishTimes.put(item.videoId, item.publishedTimeSeconds);
            }

            // Add English parsed metadata to localized results.
            List<ChannelSearchResult> mergedResults = new ArrayList<>(localized.results.size());
            for (ChannelSearchResult item : localized.results) {
                Long timeSeconds = englishTimes.get(item.videoId);
                final long publishedTimeSeconds = (timeSeconds != null)
                        ? timeSeconds : item.publishedTimeSeconds;

                mergedResults.add(new ChannelSearchResult(
                        item.videoId,
                        item.title,
                        item.metadata,
                        item.thumbnailUrl,
                        publishedTimeSeconds,
                        item.viewCount,
                        item.originalIndex
                ));
            }

            return new ChannelSearchResponse(localized.channelName, mergedResults);
        } catch (Exception ex) {
            Logger.printException(() -> "fetch parallel failed", ex);
        }

        return null;
    }

    @Nullable
    private static ChannelSearchResponse fetchSingle(String channelId, String query, Locale locale) {
        Utils.verifyOffMainThread();

        final boolean isEnglish = "en".equalsIgnoreCase(locale.getLanguage());
        final long startTime = System.currentTimeMillis();
        try {
            byte[] requestBody = ChannelSearchRoutes.createBody(channelId, query, locale);
            HttpURLConnection connection = ChannelSearchRoutes.getConnection(ChannelSearchRoutes.CHANNEL_SEARCH);
            connection.setFixedLengthStreamingMode(requestBody.length);
            connection.getOutputStream().write(requestBody);

            final int responseCode = connection.getResponseCode();
            if (responseCode == Requester.HTTP_STATUS_CODE_SUCCESS) {
                return parseResponse(Requester.parseJSONObject(connection), isEnglish);
            }
            String error = Requester.parseErrorStringAndDisconnect(connection);
            logDebugException("Channel search failed with code: " + responseCode + " error: " + error);
        } catch (SocketTimeoutException ex) {
            Logger.printInfo(() -> "Connection timeout", ex);
        } catch (IOException ex) {
            Logger.printInfo(() -> "Network error", ex);
        } catch (Exception ex) {
            Logger.printException(() -> "fetch failed", ex);
        } finally {
            Logger.printDebug(() -> "Fetched channel search (" + locale + "), took: "
                    + (System.currentTimeMillis() - startTime) + "ms");
        }
        return null;
    }

    private static ChannelSearchResponse parseResponse(JSONObject json, boolean isEnglish) {
        List<ChannelSearchResult> results = new ArrayList<>();

        try {
            JSONArray tabs = json
                    .getJSONObject("contents")
                    .getJSONObject("singleColumnBrowseResultsRenderer")
                    .getJSONArray("tabs");

            for (int i = 0, tabsLength = tabs.length(); i < tabsLength; i++) {
                JSONObject tab = tabs.getJSONObject(i).optJSONObject("tabRenderer");
                if (tab == null || !tab.optBoolean("selected")) {
                    continue;
                }

                JSONArray sections = tab
                        .getJSONObject("content")
                        .getJSONObject("sectionListRenderer")
                        .getJSONArray("contents");

                for (int j = 0, sectionsLength = sections.length(); j < sectionsLength; j++) {
                    JSONObject section = sections.getJSONObject(j).optJSONObject("itemSectionRenderer");
                    if (section == null) {
                        continue;
                    }

                    JSONArray items = section.optJSONArray("contents");
                    if (items == null) {
                        continue;
                    }

                    int index = 0;
                    for (int k = 0, itemsLength = items.length(); k < itemsLength; k++) {
                        JSONObject video = items.getJSONObject(k).optJSONObject("compactVideoRenderer");
                        if (video != null) {
                            ChannelSearchResult result = parseVideo(video, index, isEnglish);
                            if (result != null) {
                                results.add(result);
                                index++;
                            }
                        }
                    }
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "parseResponse failed", ex);
        }

        return new ChannelSearchResponse(parseChannelName(json), results);
    }

    private static String parseChannelName(JSONObject json) {
        JSONObject metadata = json.optJSONObject("metadata");
        if (metadata == null) {
            return "";
        }

        JSONObject channel = metadata.optJSONObject("channelMetadataRenderer");
        return channel == null ? "" : channel.optString("title");
    }

    @Nullable
    private static ChannelSearchResult parseVideo(JSONObject video, int index, boolean isEnglish) {
        String videoId = video.optString("videoId");
        String title = parseText(video.optJSONObject("title"));
        if (videoId.isEmpty() || title.isEmpty()) {
            return null;
        }

        String lengthText = parseText(video.optJSONObject("lengthText"));
        String shortViewCountText = parseText(video.optJSONObject("shortViewCountText"));
        String viewCountText = parseText(video.optJSONObject("viewCountText"));
        String displayViewCountText = !shortViewCountText.isEmpty() ? shortViewCountText : viewCountText;
        String publishedTimeText = parseText(video.optJSONObject("publishedTimeText"));

        StringBuilder metadata = new StringBuilder();
        appendMetadata(metadata, lengthText);
        appendMetadata(metadata, displayViewCountText);
        appendMetadata(metadata, publishedTimeText);

        long publishedTimeSeconds = isEnglish ? parsePublishedTimeSecondsAgo(publishedTimeText) : Long.MAX_VALUE;
        long viewCount = parseViewCount(viewCountText, shortViewCountText);

        return new ChannelSearchResult(videoId, title, metadata.toString(), parseThumbnail(video),
                publishedTimeSeconds, viewCount, index);
    }

    @SuppressWarnings("ConstantConditions")
    private static long parsePublishedTimeSecondsAgo(String timeText) {
        if (timeText == null || timeText.isEmpty()) {
            return Long.MAX_VALUE;
        }

        String lower = timeText.toLowerCase(Locale.ROOT);
        if (lower.contains("live") || lower.contains("now")) {
            return 0;
        }

        Matcher matcher = PATTERN_PUBLISHED_TIME.matcher(lower);
        if (!matcher.find()) {
            logDebugException("Debug: Could not parse time:" + timeText);
            return Long.MAX_VALUE;
        }

        long number;
        try {
            number = Long.parseLong(matcher.group(1));
        } catch (Exception ex) {
            logDebugException("Debug: Could not parse time:" + timeText);
            return Long.MAX_VALUE;
        }

        String unit = matcher.group(2);
        return switch (unit) {
            case "year" -> number * 365L * 24L * 3600L;
            case "month" -> number * 30L * 24L * 3600L;
            case "week" -> number * 7L * 24L * 3600L;
            case "day" -> number * 24L * 3600L;
            case "hour" -> number * 3600L;
            case "minute" -> number * 60L;
            case "second" -> number;
            default -> Long.MAX_VALUE;
        };
    }

    private static void logDebugException(String message) {
        Logger.LogMessage logMessage = () -> message;
        if (Settings.DEBUG.get()) {
            Logger.printException(logMessage);
        } else {
            Logger.printDebug(logMessage);
        }
    }

    @SuppressWarnings("ConstantConditions")
    private static long parseViewCount(String viewCountText, String shortViewCountText) {
        if (viewCountText != null && !viewCountText.isEmpty()) {
            String digitsOnly = viewCountText.replaceAll("[^0-9]", "");
            if (!digitsOnly.isEmpty()) {
                return Long.parseLong(digitsOnly);
            }
        }

        String textToParse = (shortViewCountText != null && !shortViewCountText.isEmpty())
                ? shortViewCountText : viewCountText;
        if (textToParse == null || textToParse.isEmpty()) {
            return 0;
        }

        String lower = textToParse.toLowerCase(Locale.ROOT);
        if (lower.contains("no view")) {
            return 0;
        }

        long multiplier = 1;
        if (lower.contains("b")) {
            multiplier = 1_000_000_000L;
        } else if (lower.contains("m")) {
            multiplier = 1_000_000L;
        } else if (lower.contains("k")) {
            multiplier = 1_000L;
        }

        Matcher matcher = Pattern.compile("(\\d+([.,]\\d+)?)").matcher(lower);
        if (!matcher.find()) {
            return 0;
        }

        String numStr = matcher.group(1);
        try {
            if (multiplier > 1) {
                numStr = numStr.replace(',', '.');
                final double val = Double.parseDouble(numStr);
                return (long) (val * multiplier);
            }
            String digitsOnly = numStr.replaceAll("[.,\\s]", "");
            return Long.parseLong(digitsOnly);
        } catch (Exception ex) {
            logDebugException("Could not parse view count: " + viewCountText
                    + " shortText:" + shortViewCountText );
            return 0;
        }
    }

    private static void appendMetadata(StringBuilder metadata, String value) {
        if (value.isEmpty()) {
            return;
        }
        //noinspection SizeReplaceableByIsEmpty
        if (metadata.length() > 0) {
            metadata.append("  •  ");
        }
        metadata.append(value);
    }

    /**
     * Text is either a plain string or a list of runs, depending on the field.
     */
    private static String parseText(@Nullable JSONObject text) {
        if (text == null) {
            return "";
        }

        String simpleText = text.optString("simpleText");
        if (!simpleText.isEmpty()) {
            return simpleText;
        }

        JSONArray runs = text.optJSONArray("runs");
        if (runs == null) {
            return "";
        }

        StringBuilder builder = new StringBuilder();
        for (int i = 0, length = runs.length(); i < length; i++) {
            JSONObject run = runs.optJSONObject(i);
            if (run != null) {
                builder.append(run.optString("text"));
            }
        }
        return builder.toString();
    }

    /**
     * Widest thumbnail that is always present, so rows do not load a needlessly large image.
     */
    private static String parseThumbnail(JSONObject video) {
        JSONObject thumbnail = video.optJSONObject("thumbnail");
        if (thumbnail == null) {
            return "";
        }

        JSONArray thumbnails = thumbnail.optJSONArray("thumbnails");
        if (thumbnails == null) {
            return "";
        }

        String best = "";
        for (int i = 0, length = thumbnails.length(); i < length; i++) {
            JSONObject entry = thumbnails.optJSONObject(i);
            if (entry != null && entry.optInt("width") <= 360) {
                best = entry.optString("url");
            }
        }
        return best;
    }
}
