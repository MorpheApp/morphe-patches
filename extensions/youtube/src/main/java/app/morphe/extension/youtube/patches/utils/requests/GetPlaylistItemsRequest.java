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
import java.util.HashMap;
import java.util.Map;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.requests.Requester;

public class GetPlaylistItemsRequest {

    private GetPlaylistItemsRequest() {
    }

    @Nullable
    public static Map<String, String> fetch(String playlistId, Map<String, String> requestHeader) {
        Utils.verifyOffMainThread();
        final long startTime = System.currentTimeMillis();
        Logger.printDebug(() -> "Fetching playlist items for: " + playlistId);

        try {
            byte[] requestBody = PlaylistRoutes.browsePlaylistBody(playlistId);
            HttpURLConnection connection = PlaylistRoutes.getConnection(PlaylistRoutes.BROWSE_PLAYLIST, requestHeader);
            connection.setFixedLengthStreamingMode(requestBody.length);
            connection.getOutputStream().write(requestBody);
            int responseCode = connection.getResponseCode();
            if (responseCode == 200) {
                JSONObject json = Requester.parseJSONObject(connection);
                return parseResponse(json);
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
    private static Map<String, String> parseResponse(JSONObject json) {
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

            Map<String, String> result = new HashMap<>();
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
                if (!videoId.isEmpty() && !setVideoId.isEmpty()) {
                    result.put(videoId, setVideoId);
                }
            }

            return result.isEmpty() ? null : result;
        } catch (JSONException e) {
            Logger.printException(() -> "parseResponse failed", e);
        }
        return null;
    }
}
