/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches.utils.requests;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.requests.Requester;

public class GetPlaylistItemsRequest {

    /**
     * Metadata available directly in a playlist browse response.
     *
     * @param durationSeconds zero when YouTube does not provide a parseable duration
     */
    public record PlaylistItem(
            String videoId,
            String setVideoId,
            String title,
            String channel,
            long durationSeconds
    ) {
    }

    private GetPlaylistItemsRequest() {
    }

    @Nullable
    public static Map<String, String> fetch(String playlistId, Map<String, String> requestHeader) {
        List<PlaylistItem> items = fetchItems(playlistId, requestHeader);
        if (items == null) return null;

        Map<String, String> result = new HashMap<>();
        for (PlaylistItem item : items) {
            if (!item.setVideoId().isEmpty()) result.put(item.videoId(), item.setVideoId());
        }
        return result.isEmpty() ? null : result;
    }

    /**
     * Fetches ordered playlist items with the metadata needed by queue pickers.
     * Passing {@code WL} requests the signed-in account's Watch Later playlist ({@code VLWL}).
     */
    @Nullable
    public static List<PlaylistItem> fetchItems(String playlistId, Map<String, String> requestHeader) {
        Utils.verifyOffMainThread();
        final long startTime = System.currentTimeMillis();
        Logger.printDebug(() -> "Fetching playlist items for: " + playlistId);

        try {
            byte[] requestBody = PlaylistRoutes.browsePlaylistBody(playlistId);
            HttpURLConnection connection = PlaylistRoutes.getConnection(PlaylistRoutes.BROWSE_PLAYLIST, requestHeader);
            connection.setFixedLengthStreamingMode(requestBody.length);
            connection.getOutputStream().write(requestBody);
            final int responseCode = connection.getResponseCode();
            if (responseCode == Requester.HTTP_STATUS_CODE_SUCCESS) {
                JSONObject json = Requester.parseJSONObject(connection);
                return parseItems(json);
            }
            Logger.printInfo(() -> "Browse playlist failed with code: " + responseCode);
        } catch (SocketTimeoutException ex) {
            Logger.printInfo(() -> "Connection timeout", ex);
        } catch (IOException ex) {
            Logger.printInfo(() -> "Network error", ex);
        } catch (Exception ex) {
            Logger.printException(() -> "fetch failed", ex);
        } finally {
            Logger.printDebug(() -> "playlist items fetch took: " + (System.currentTimeMillis() - startTime) + "ms");
        }
        return null;
    }

    @Nullable
    private static JSONArray findPlaylistContents(JSONArray sectionContents) throws JSONException {
        for (int i = 0, length = sectionContents.length(); i < length; i++) {
            JSONObject section = sectionContents.getJSONObject(i);
            if (section.has("playlistVideoListRenderer")) {
                return section.getJSONObject("playlistVideoListRenderer").getJSONArray("contents");
            }
            if (section.has("itemSectionRenderer")) {
                JSONArray inner = section.getJSONObject("itemSectionRenderer").getJSONArray("contents");
                for (int j = 0, innerLength = inner.length(); j < innerLength; j++) {
                    JSONObject innerItem = inner.getJSONObject(j);
                    if (innerItem.has("playlistVideoListRenderer")) {
                        return innerItem.getJSONObject("playlistVideoListRenderer").getJSONArray("contents");
                    }
                }
            }
        }
        return null;
    }

    @Nullable
    static List<PlaylistItem> parseItems(JSONObject json) {
        try {
            if (!json.has("contents")) {
                Logger.printDebug(() -> "JSON contents are missing, cannot parse response");
                return null;
            }
            JSONObject contents = json.getJSONObject("contents");
            JSONObject columnRenderer = contents.optJSONObject("singleColumnBrowseResultsRenderer");
            if (columnRenderer == null) {
                columnRenderer = contents.optJSONObject("twoColumnBrowseResultsRenderer");
            }
            if (columnRenderer == null) {
                return null;
            }

            JSONArray sectionContents = columnRenderer
                    .getJSONArray("tabs")
                    .getJSONObject(0)
                    .getJSONObject("tabRenderer")
                    .getJSONObject("content")
                    .getJSONObject("sectionListRenderer")
                    .getJSONArray("contents");

            JSONArray playlistContents = findPlaylistContents(sectionContents);
            if (playlistContents == null) {
                return null;
            }

            List<PlaylistItem> result = new ArrayList<>();
            for (int i = 0, length = playlistContents.length(); i < length; i++) {
                JSONObject element = playlistContents.optJSONObject(i);
                if (element == null) {
                    continue;
                }
                JSONObject renderer = element.optJSONObject("playlistVideoRenderer");
                if (renderer == null) {
                    continue;
                }
                String videoId = renderer.optString("videoId");
                String setVideoId = renderer.optString("setVideoId");
                if (!videoId.isEmpty()) {
                    result.add(new PlaylistItem(
                            videoId,
                            setVideoId,
                            text(renderer.optJSONObject("title")),
                            text(renderer.optJSONObject("shortBylineText")),
                            durationSeconds(renderer)
                    ));
                }
            }

            return result.isEmpty() ? null : result;
        } catch (JSONException e) {
            Logger.printException(() -> "parseResponse failed", e);
        }
        return null;
    }

    private static String text(@Nullable JSONObject value) {
        if (value == null) return "";
        String simple = value.optString("simpleText");
        if (!simple.isEmpty()) return simple;

        JSONArray runs = value.optJSONArray("runs");
        if (runs == null) return "";
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < runs.length(); i++) {
            JSONObject run = runs.optJSONObject(i);
            if (run != null) result.append(run.optString("text"));
        }
        return result.toString();
    }

    private static long durationSeconds(JSONObject renderer) {
        long numeric = renderer.optLong("lengthSeconds", 0);
        if (numeric > 0) return numeric;

        String formatted = text(renderer.optJSONObject("lengthText"));
        if (formatted.isEmpty()) return 0;
        long total = 0;
        String[] parts = formatted.split(":");
        try {
            for (String part : parts) total = Math.addExact(Math.multiplyExact(total, 60), Long.parseLong(part));
            return total;
        } catch (ArithmeticException | NumberFormatException ignored) {
            return 0;
        }
    }
}
