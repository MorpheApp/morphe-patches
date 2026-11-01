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
import app.morphe.extension.shared.requests.Route;

public class GetPlaylistItemsRequest {

    /**
     * A playlist has at most 5000 videos, and a page after the first has 60 videos.
     */
    private static final int MAX_PLAYLIST_PAGES = 100;

    private GetPlaylistItemsRequest() {
    }

    /**
     * @return The ids of the videos of all the pages of the playlist, with the id of their entry in the
     *         playlist if it's known, or null if the request failed. Only a failure of the first page
     *         returns null, as the videos of the other pages are still valid.
     */
    @Nullable
    public static Map<String, String> fetchVideoIds(String playlistId, Map<String, String> requestHeader) {
        Utils.verifyOffMainThread();
        final long startTime = System.currentTimeMillis();

        try {
            JSONObject response = sendRequest(PlaylistRoutes.BROWSE_PLAYLIST_VIDEO_IDS,
                    PlaylistRoutes.browsePlaylistBody(playlistId), requestHeader);
            if (response == null) {
                return null;
            }

            Map<String, String> videos = new HashMap<>();
            JSONObject page = findPlaylistRenderer(response);
            for (int i = 0; page != null && i < MAX_PLAYLIST_PAGES; i++) {
                JSONArray contents = page.optJSONArray("contents");
                if (contents != null) {
                    for (int j = 0, length = contents.length(); j < length; j++) {
                        JSONObject element = contents.optJSONObject(j);
                        JSONObject renderer = element == null ? null : element.optJSONObject("playlistVideoRenderer");
                        String videoId = renderer == null ? "" : renderer.optString("videoId");
                        if (!videoId.isEmpty()) {
                            videos.put(videoId, renderer.optString("setVideoId"));
                        }
                    }
                }

                JSONArray continuations = page.optJSONArray("continuations");
                if (continuations == null || continuations.length() == 0) {
                    break;
                }
                response = sendRequest(PlaylistRoutes.BROWSE_PLAYLIST_VIDEO_IDS,
                        PlaylistRoutes.browseContinuationBody(continuations.getJSONObject(0)
                                .getJSONObject("nextContinuationData").getString("continuation")),
                        requestHeader);
                if (response == null) {
                    break;
                }
                JSONObject continuationContents = response.optJSONObject("continuationContents");
                page = continuationContents == null
                        ? null
                        : continuationContents.optJSONObject("playlistVideoListContinuation");
            }
            Logger.printDebug(() -> "Fetched " + videos.size() + " video ids of playlist: " + playlistId);
            return videos;
        } catch (SocketTimeoutException ex) {
            Logger.printInfo(() -> "Connection timeout", ex);
        } catch (IOException ex) {
            Logger.printInfo(() -> "Network error", ex);
        } catch (Exception ex) {
            Logger.printException(() -> "fetchVideoIds failed", ex);
        } finally {
            Logger.printDebug(() -> "playlist video ids fetch took: " + (System.currentTimeMillis() - startTime) + "ms");
        }
        return null;
    }

    @Nullable
    private static JSONObject sendRequest(Route.CompiledRoute route, byte[] requestBody, Map<String, String> requestHeader)
            throws IOException, JSONException {
        HttpURLConnection connection = PlaylistRoutes.getConnection(route, requestHeader);
        connection.setFixedLengthStreamingMode(requestBody.length);
        connection.getOutputStream().write(requestBody);
        final int responseCode = connection.getResponseCode();
        if (responseCode == Requester.HTTP_STATUS_CODE_SUCCESS) {
            return Requester.parseJSONObject(connection);
        }
        Logger.printInfo(() -> "Browse playlist failed with code: " + responseCode);
        return null;
    }

    @Nullable
    public static Map<String, String> fetch(String playlistId, Map<String, String> requestHeader) {
        Utils.verifyOffMainThread();
        final long startTime = System.currentTimeMillis();
        Logger.printDebug(() -> "Fetching playlist items for: " + playlistId);

        try {
            JSONObject json = sendRequest(PlaylistRoutes.BROWSE_PLAYLIST,
                    PlaylistRoutes.browsePlaylistBody(playlistId), requestHeader);
            if (json != null) {
                return parseResponse(json);
            }
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

    /**
     * @return The renderer of the first page of the videos, or null if the playlist has no videos.
     */
    @Nullable
    private static JSONObject findPlaylistRenderer(JSONObject json) throws JSONException {
        JSONObject contents = json.optJSONObject("contents");
        if (contents == null) {
            Logger.printDebug(() -> "JSON contents are missing, cannot parse response");
            return null;
        }
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

        for (int i = 0, length = sectionContents.length(); i < length; i++) {
            JSONObject section = sectionContents.getJSONObject(i);
            if (section.has("playlistVideoListRenderer")) {
                return section.getJSONObject("playlistVideoListRenderer");
            }
            if (section.has("itemSectionRenderer")) {
                JSONArray inner = section.getJSONObject("itemSectionRenderer").getJSONArray("contents");
                for (int j = 0, innerLength = inner.length(); j < innerLength; j++) {
                    JSONObject innerItem = inner.getJSONObject(j);
                    if (innerItem.has("playlistVideoListRenderer")) {
                        return innerItem.getJSONObject("playlistVideoListRenderer");
                    }
                }
            }
        }
        return null;
    }

    @Nullable
    private static Map<String, String> parseResponse(JSONObject json) {
        try {
            JSONObject playlistRenderer = findPlaylistRenderer(json);
            JSONArray playlistContents = playlistRenderer == null ? null : playlistRenderer.getJSONArray("contents");
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
