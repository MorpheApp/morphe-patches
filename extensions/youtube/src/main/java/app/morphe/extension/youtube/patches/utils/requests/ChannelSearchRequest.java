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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.requests.Requester;

public final class ChannelSearchRequest {

    public static final class ChannelSearchResult {
        public final String videoId;
        public final String title;
        public final String metadata;
        public final String thumbnailUrl;
        public final long publishedTimeSeconds;
        /** Negative if not known. */
        public final long viewCount;
        public final long lengthSeconds;
        public final int originalIndex;

        private ChannelSearchResult(String videoId, String title, String metadata, String thumbnailUrl,
                                    long publishedTimeSeconds, long viewCount, long lengthSeconds, int originalIndex) {
            this.videoId = videoId;
            this.title = title;
            this.metadata = metadata;
            this.thumbnailUrl = thumbnailUrl;
            this.publishedTimeSeconds = publishedTimeSeconds;
            this.viewCount = viewCount;
            this.lengthSeconds = lengthSeconds;
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
    /**
     * The search stops by itself after a different number of pages every time,
     * so these only bound searches of the largest channels.
     */
    private static final int MAX_SEARCH_RESULTS = 1000;
    private static final int MAX_CONTINUATION_PAGES = 50;

    private static final Pattern PATTERN_VIEW_COUNT = Pattern.compile("(\\d+([.,]\\d+)?)");

    private static final Pattern PATTERN_PUBLISHED_TIME = Pattern.compile(
            "(\\d+)\\s*(year|month|week|day|hour|minute|second)");

    public interface ChannelSearchCallback {
        /**
         * Called on a background thread after each page, and on the calling thread if the search is cached.
         *
         * @param response   Null if the search failed.
         * @param isComplete Whether this is the last page.
         */
        void onResponsePage(@Nullable ChannelSearchResponse response, boolean isComplete);
        boolean isCancelled();
    }

    /**
     * Complete searches only, as a search that was cancelled before its last page is not.
     */
    private static final Map<String, ChannelSearchResponse> cache = Collections.synchronizedMap(
            Utils.createSizeRestrictedMap(10));

    private ChannelSearchRequest() {
    }

    public static void fetchProgressive(String channelId, String query, ChannelSearchCallback callback) {
        String key = channelId + "\n" + query;
        ChannelSearchResponse cached = cache.get(key);
        if (cached != null) {
            callback.onResponsePage(cached, true);
            return;
        }

        Utils.runOnBackgroundThread(() -> {
            ChannelSearchResponse response = fetch(channelId, query, callback);
            if (response != null && !callback.isCancelled()) {
                cache.put(key, response);
            }
        });
    }

    @Nullable
    private static ChannelSearchResponse fetch(String channelId, String query, ChannelSearchCallback callback) {
        Utils.verifyOffMainThread();

        Locale appLocale = Requester.getAppLocale();
        final boolean isEnglish = appLocale.getLanguage().isEmpty() || "en".equalsIgnoreCase(appLocale.getLanguage());

        if (isEnglish) {
            return fetchSingle(channelId, query, Locale.US, callback);
        }

        ChannelSearchCallback cancelProxy = new ChannelSearchCallback() {
            @Override
            public void onResponsePage(@Nullable ChannelSearchResponse response, boolean isComplete) {}

            @Override
            public boolean isCancelled() {
                return callback.isCancelled();
            }
        };

        Future<ChannelSearchResponse> localizedFuture = Utils.submitOnBackgroundThread(
                () -> fetchSingle(channelId, query, appLocale, cancelProxy));
        Future<ChannelSearchResponse> englishFuture = Utils.submitOnBackgroundThread(
                () -> fetchSingle(channelId, query, Locale.US, cancelProxy));

        ChannelSearchResponse merged = null;
        try {
            ChannelSearchResponse english = englishFuture.get(MAX_MILLISECONDS_TO_WAIT_FOR_FETCH, TimeUnit.MILLISECONDS);
            ChannelSearchResponse localized = localizedFuture.get(MAX_MILLISECONDS_TO_WAIT_FOR_FETCH, TimeUnit.MILLISECONDS);
            merged = localized == null || english == null
                    ? (localized == null ? english : localized)
                    : merge(localized, english);
        } catch (Exception ex) {
            Logger.printException(() -> "fetch parallel failed", ex);
        }

        callback.onResponsePage(merged, true);
        return merged;
    }

    /**
     * The localized results, with the age, view count and length parsed from the English ones.
     */
    private static ChannelSearchResponse merge(ChannelSearchResponse localized, ChannelSearchResponse english) {
        Map<String, ChannelSearchResult> englishMap = new HashMap<>(2 * english.results.size());
        for (ChannelSearchResult item : english.results) {
            englishMap.put(item.videoId, item);
        }

        List<ChannelSearchResult> mergedResults = new ArrayList<>(localized.results.size());
        for (ChannelSearchResult item : localized.results) {
            ChannelSearchResult englishItem = englishMap.get(item.videoId);
            mergedResults.add(englishItem == null ? item : new ChannelSearchResult(
                    item.videoId,
                    item.title,
                    item.metadata,
                    item.thumbnailUrl,
                    englishItem.publishedTimeSeconds,
                    englishItem.viewCount,
                    englishItem.lengthSeconds,
                    item.originalIndex
            ));
        }

        return new ChannelSearchResponse(localized.channelName, mergedResults);
    }

    @Nullable
    private static ChannelSearchResponse fetchSingle(String channelId, String query,
                                                     Locale locale, ChannelSearchCallback callback) {
        Utils.verifyOffMainThread();

        final boolean isEnglish = "en".equalsIgnoreCase(locale.getLanguage());
        final long startTime = System.currentTimeMillis();
        List<ChannelSearchResult> allResults = new ArrayList<>();
        Set<String> seenVideoIds = new HashSet<>();

        try {
            JSONObject json = ChannelSearchRoutes.fetch(ChannelSearchRoutes.CHANNEL_SEARCH,
                    ChannelSearchRoutes.createBody(channelId, query, locale));
            if (json == null) {
                callback.onResponsePage(null, true);
                return null;
            }

            String channelName = parseChannelName(json);
            int[] indexRef = {0};
            for (int page = 1; ; page++) {
                String browseToken = parseBrowseContents(json, allResults, seenVideoIds, indexRef, isEnglish);
                String actionToken = parseContinuationActions(json, allResults, seenVideoIds, indexRef, isEnglish);
                String nextToken = browseToken != null ? browseToken : actionToken;
                final boolean isLast = nextToken == null || nextToken.isEmpty()
                        || page >= MAX_CONTINUATION_PAGES || allResults.size() >= MAX_SEARCH_RESULTS;
                ChannelSearchResponse response = new ChannelSearchResponse(channelName, new ArrayList<>(allResults));
                callback.onResponsePage(response, isLast);
                if (isLast) {
                    return response;
                }
                if (callback.isCancelled()) {
                    Logger.printDebug(() -> "Channel search cancelled");
                    return response;
                }

                json = ChannelSearchRoutes.fetch(ChannelSearchRoutes.CHANNEL_SEARCH,
                        ChannelSearchRoutes.createContinuationBody(nextToken, locale));
                if (json == null) {
                    // A page that could not be fetched ends the search with the results so far.
                    callback.onResponsePage(response, true);
                    return response;
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "fetch failed", ex);
            callback.onResponsePage(null, true);
        } finally {
            Logger.printDebug(() -> "Fetched channel search (" + locale + "), items=" + allResults.size()
                    + " took: " + (System.currentTimeMillis() - startTime) + "ms");
        }
        return null;
    }

    @Nullable
    private static String parseBrowseContents(JSONObject json, List<ChannelSearchResult> results,
                                             Set<String> seenVideoIds, int[] indexRef, boolean isEnglish) {
        JSONObject contents = json.optJSONObject("contents");
        if (contents == null) return null;

        JSONObject singleCol = contents.optJSONObject("singleColumnBrowseResultsRenderer");
        if (singleCol == null) return null;

        JSONArray tabs = singleCol.optJSONArray("tabs");
        if (tabs == null) return null;

        String nextToken = null;
        for (int i = 0, tabsLength = tabs.length(); i < tabsLength; i++) {
            JSONObject tab = tabs.optJSONObject(i);
            if (tab == null) continue;

            JSONObject tabRenderer = tab.optJSONObject("tabRenderer");
            if (tabRenderer == null || !tabRenderer.optBoolean("selected")) continue;

            JSONObject content = tabRenderer.optJSONObject("content");
            if (content == null) continue;

            JSONObject sectionList = content.optJSONObject("sectionListRenderer");
            if (sectionList == null) continue;

            JSONArray sections = sectionList.optJSONArray("contents");
            if (sections == null) continue;

            for (int j = 0, sectionsLength = sections.length(); j < sectionsLength; j++) {
                JSONObject section = sections.optJSONObject(j);
                if (section == null) continue;

                JSONObject itemSection = section.optJSONObject("itemSectionRenderer");
                if (itemSection != null) {
                    parseVideoArray(itemSection.optJSONArray("contents"), results, seenVideoIds, indexRef, isEnglish);
                }

                String tok = extractContinuationToken(section.optJSONObject("continuationItemRenderer"));
                if (tok != null) nextToken = tok;
            }
        }
        return nextToken;
    }

    @Nullable
    private static String parseContinuationActions(JSONObject json, List<ChannelSearchResult> results,
                                                   Set<String> seenVideoIds, int[] indexRef, boolean isEnglish) {
        JSONArray actions = json.optJSONArray("onResponseReceivedActions");
        if (actions == null) return null;

        String nextToken = null;
        for (int i = 0, actionsLength = actions.length(); i < actionsLength; i++) {
            JSONObject action = actions.optJSONObject(i);
            if (action == null) continue;

            JSONObject appendAction = action.optJSONObject("appendContinuationItemsAction");
            if (appendAction == null) continue;

            JSONArray continuationItems = appendAction.optJSONArray("continuationItems");
            if (continuationItems == null) continue;

            for (int j = 0, itemsLength = continuationItems.length(); j < itemsLength; j++) {
                JSONObject item = continuationItems.optJSONObject(j);
                if (item == null) continue;

                JSONObject isr = item.optJSONObject("itemSectionRenderer");
                if (isr != null) {
                    parseVideoArray(isr.optJSONArray("contents"), results, seenVideoIds, indexRef, isEnglish);
                } else {
                    JSONObject cvr = item.optJSONObject("compactVideoRenderer");
                    if (cvr != null) {
                        parseSingleVideo(cvr, results, seenVideoIds, indexRef, isEnglish);
                    }
                }

                String tok = extractContinuationToken(item.optJSONObject("continuationItemRenderer"));
                if (tok != null) nextToken = tok;
            }
        }
        return nextToken;
    }

    private static void parseVideoArray(@Nullable JSONArray items, List<ChannelSearchResult> results,
                                       Set<String> seenVideoIds, int[] indexRef, boolean isEnglish) {
        if (items == null) return;

        for (int i = 0, length = items.length(); i < length; i++) {
            JSONObject item = items.optJSONObject(i);
            if (item != null) {
                JSONObject cvr = item.optJSONObject("compactVideoRenderer");
                if (cvr != null) {
                    parseSingleVideo(cvr, results, seenVideoIds, indexRef, isEnglish);
                }
            }
        }
    }

    private static void parseSingleVideo(JSONObject video, List<ChannelSearchResult> results,
                                        Set<String> seenVideoIds, int[] indexRef, boolean isEnglish) {
        ChannelSearchResult result = parseVideo(video, indexRef[0], isEnglish);
        if (result != null && seenVideoIds.add(result.videoId)) {
            results.add(result);
            indexRef[0]++;
        }
    }

    @Nullable
    private static String extractContinuationToken(@Nullable JSONObject continuationItemRenderer) {
        if (continuationItemRenderer == null) {
            return null;
        }
        try {
            return continuationItemRenderer
                    .getJSONObject("continuationEndpoint")
                    .getJSONObject("continuationCommand")
                    .getString("token");
        } catch (Exception ex) {
            Logger.printDebug(() -> "Could not extract continuation token: " + continuationItemRenderer);
            return null;
        }
    }

    private static String parseChannelName(JSONObject json) {
        JSONObject metadata = json.optJSONObject("metadata");
        if (metadata == null) {
            return "";
        }

        JSONObject channel = metadata.optJSONObject("channelMetadataRenderer");
        return channel == null ? "" : channel.optString("title");
    }

    /**
     * An upload of the channel, which is not a search result. It comes after
     * the search results that are equal to it in a sort.
     *
     * @param details Localized view count and age of the upload, as shown under the title.
     *                Both are rounded, so the exact ones are fetched with the details of the video.
     */
    static ChannelSearchResult createResult(String videoId, String title, String thumbnailUrl,
                                            String lengthText, List<String> details) {
        StringBuilder metadata = new StringBuilder();
        appendMetadata(metadata, lengthText);
        for (String detail : details) {
            appendMetadata(metadata, detail);
        }

        // The age is localized and rounded, and the place in the uploads is used instead.
        return new ChannelSearchResult(videoId, title, metadata.toString(), thumbnailUrl,
                Long.MAX_VALUE, -1, parseLengthSeconds(lengthText), Integer.MAX_VALUE);
    }

    @Nullable
    private static ChannelSearchResult parseVideo(JSONObject video, int index, boolean isEnglish) {
        String videoId = video.optString("videoId");
        String title = parseText(video.optJSONObject("title"));
        if (videoId.isEmpty() || title.isEmpty()) {
            Logger.printDebug(() -> "Could not parse videoId: " + videoId);
            return null;
        }

        String lengthText = parseText(video.optJSONObject("lengthText"));
        String viewCountText = parseText(video.optJSONObject("viewCountText"));
        String shortViewCountText = parseText(video.optJSONObject("shortViewCountText"));
        String displayViewCountText = !shortViewCountText.isEmpty() ? shortViewCountText : viewCountText;
        String publishedTimeText = parseText(video.optJSONObject("publishedTimeText"));

        StringBuilder metadata = new StringBuilder();
        appendMetadata(metadata, lengthText);
        appendMetadata(metadata, displayViewCountText);
        appendMetadata(metadata, publishedTimeText);

        final long publishedTimeSeconds = isEnglish ? parsePublishedTimeSecondsAgo(publishedTimeText) : Long.MAX_VALUE;
        final long viewCount = parseViewCount(viewCountText, shortViewCountText);
        final long lengthSeconds = parseLengthSeconds(lengthText);

        return new ChannelSearchResult(videoId, title, metadata.toString(), parseThumbnail(video),
                publishedTimeSeconds, viewCount, lengthSeconds, index);
    }

    private static long parseLengthSeconds(String lengthText) {
        if (lengthText == null || lengthText.isEmpty()) {
            return 0;
        }

        String[] parts = lengthText.trim().split(":");
        try {
            long totalSeconds = 0;
            for (String part : parts) {
                totalSeconds = totalSeconds * 60 + Long.parseLong(part.trim());
            }
            return totalSeconds;
        } catch (Exception ex) {
            Logger.printDebug(() -> "Could not parse length: " + lengthText);
            return 0;
        }
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
            Logger.printDebug(() -> "Could not parse time:" + timeText);
            return Long.MAX_VALUE;
        }

        final long number;
        try {
            number = Long.parseLong(matcher.group(1));
        } catch (Exception ex) {
            Logger.printDebug(() -> "Could not parse time:" + timeText);
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

    /**
     * @param viewCountText      Exact view count, such as "8,512,345 views", or empty.
     * @param shortViewCountText Rounded English view count, such as "8.5M views".
     */
    @SuppressWarnings("ConstantConditions")
    static long parseViewCount(String viewCountText, String shortViewCountText) {
        if (viewCountText != null && !viewCountText.isEmpty()) {
            String digitsOnly = viewCountText.replaceAll("[^0-9]", "");
            if (!digitsOnly.isEmpty()) {
                return Long.parseLong(digitsOnly);
            }
        }

        String textToParse = (shortViewCountText != null && !shortViewCountText.isEmpty())
                ? shortViewCountText : viewCountText;
        if (textToParse == null || textToParse.isEmpty()) {
            Logger.printDebug(() -> "Could not parse view count: " + shortViewCountText);
            return 0;
        }

        String lower = textToParse.toLowerCase(Locale.ROOT);
        if (lower.contains("no view")) {
            return 0;
        }

        final long multiplier;
        if (lower.contains("b")) {
            multiplier = 1_000_000_000L;
        } else if (lower.contains("m")) {
            multiplier = 1_000_000L;
        } else if (lower.contains("k")) {
            multiplier = 1_000L;
        } else {
            multiplier = 1;
        }

        Matcher matcher = PATTERN_VIEW_COUNT.matcher(lower);
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
            Logger.printDebug(() -> "Could not parse view count: " + viewCountText
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
        return thumbnail == null ? "" : widestThumbnail(thumbnail.optJSONArray("thumbnails"));
    }

    /**
     * @param thumbnails Thumbnails of different sizes, each with its url and width.
     */
    static String widestThumbnail(@Nullable JSONArray thumbnails) {
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
