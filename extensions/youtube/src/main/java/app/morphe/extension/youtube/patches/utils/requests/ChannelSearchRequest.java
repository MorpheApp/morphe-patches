/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2964
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.requests.Requester;

public final class ChannelSearchRequest {

    public static final class ChannelSearchResult {
        public final String videoId;
        public final String title;
        public final String metadata;
        public final String thumbnailUrl;

        private ChannelSearchResult(String videoId, String title, String metadata, String thumbnailUrl) {
            this.videoId = videoId;
            this.title = title;
            this.metadata = metadata;
            this.thumbnailUrl = thumbnailUrl;
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

        final long startTime = System.currentTimeMillis();
        try {
            byte[] requestBody = ChannelSearchRoutes.createBody(channelId, query);
            HttpURLConnection connection = ChannelSearchRoutes.getConnection(ChannelSearchRoutes.CHANNEL_SEARCH);
            connection.setFixedLengthStreamingMode(requestBody.length);
            connection.getOutputStream().write(requestBody);

            final int responseCode = connection.getResponseCode();
            if (responseCode == Requester.HTTP_STATUS_CODE_SUCCESS) {
                return parseResponse(Requester.parseJSONObject(connection));
            }
            String error = Requester.parseErrorStringAndDisconnect(connection);
            Logger.printInfo(() -> "Channel search failed with code: " + responseCode + " error: " + error);
        } catch (SocketTimeoutException ex) {
            Logger.printInfo(() -> "Connection timeout", ex);
        } catch (IOException ex) {
            Logger.printInfo(() -> "Network error", ex);
        } catch (Exception ex) {
            Logger.printException(() -> "fetch failed", ex);
        } finally {
            Logger.printDebug(() -> "Fetched channel search, took: "
                    + (System.currentTimeMillis() - startTime) + "ms");
        }
        return null;
    }

    private static ChannelSearchResponse parseResponse(JSONObject json) {
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

                    for (int k = 0, itemsLength = items.length(); k < itemsLength; k++) {
                        JSONObject video = items.getJSONObject(k).optJSONObject("compactVideoRenderer");
                        if (video != null) {
                            ChannelSearchResult result = parseVideo(video);
                            if (result != null) {
                                results.add(result);
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
    private static ChannelSearchResult parseVideo(JSONObject video) {
        String videoId = video.optString("videoId");
        String title = parseText(video.optJSONObject("title"));
        if (videoId.isEmpty() || title.isEmpty()) {
            return null;
        }

        StringBuilder metadata = new StringBuilder();
        appendMetadata(metadata, parseText(video.optJSONObject("lengthText")));
        appendMetadata(metadata, parseText(video.optJSONObject("shortViewCountText")));
        appendMetadata(metadata, parseText(video.optJSONObject("publishedTimeText")));

        return new ChannelSearchResult(videoId, title, metadata.toString(), parseThumbnail(video));
    }

    private static void appendMetadata(StringBuilder metadata, String value) {
        if (value.isEmpty()) {
            return;
        }
        if (metadata.length() != 0) {
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
