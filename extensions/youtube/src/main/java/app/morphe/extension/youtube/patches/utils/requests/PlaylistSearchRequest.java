/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.requests.Requester;

public final class PlaylistSearchRequest {

    public static final class PlaylistSearchResult {
        public final String videoId;
        public final String setVideoId;
        public final String title;
        public final String metadata;
        public final String thumbnailUrl;

        private PlaylistSearchResult(
                String videoId,
                String setVideoId,
                String title,
                String metadata,
                String thumbnailUrl
        ) {
            this.videoId = videoId;
            this.setVideoId = setVideoId;
            this.title = title;
            this.metadata = metadata;
            this.thumbnailUrl = thumbnailUrl;
        }
    }

    public static final class PlaylistSearchResponse {
        public final List<PlaylistSearchResult> results;

        private PlaylistSearchResponse(List<PlaylistSearchResult> results) {
            this.results = results;
        }
    }

    private static final int MAX_MILLISECONDS_TO_WAIT_FOR_FETCH = 20 * 1000;
    private static final int MAX_CONTINUATION_PAGES = 100;

    private static final Map<String, PlaylistSearchRequest> cache =
            Collections.synchronizedMap(Utils.createSizeRestrictedMap(10));

    private final Future<PlaylistSearchResponse> future;

    private PlaylistSearchRequest(String playlistId, String query, Map<String, String> requestHeader) {
        future = Utils.submitOnBackgroundThread(() -> fetch(playlistId, query, requestHeader));
    }

    public static PlaylistSearchRequest fetchRequestIfNeeded(
            String playlistId,
            String query,
            Map<String, String> requestHeader
    ) {
        return cache.computeIfAbsent(
                playlistId + "\n" + query,
                key -> new PlaylistSearchRequest(playlistId, query, requestHeader)
        );
    }

    @Nullable
    public PlaylistSearchResponse getResponse() {
        try {
            return future.get(
                    MAX_MILLISECONDS_TO_WAIT_FOR_FETCH,
                    TimeUnit.MILLISECONDS
            );
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
    private static PlaylistSearchResponse fetch(
            String playlistId,
            String query,
            Map<String, String> requestHeader
    ) {
        Utils.verifyOffMainThread();

        final long startTime = System.currentTimeMillis();
        final List<PlaylistSearchResult> results = new ArrayList<>();
        final Set<String> seenVideoIds = new HashSet<>();

        try {
            String continuation = null;

            for (int page = 0; page < MAX_CONTINUATION_PAGES; page++) {
                byte[] requestBody = continuation == null
                        ? PlaylistRoutes.browsePlaylistBody(playlistId)
                        : PlaylistRoutes.browsePlaylistContinuationBody(continuation);

                HttpURLConnection connection = PlaylistRoutes.getConnection(
                        PlaylistRoutes.BROWSE_PLAYLIST,
                        requestHeader
                );
                connection.setFixedLengthStreamingMode(requestBody.length);
                connection.getOutputStream().write(requestBody);

                final int responseCode = connection.getResponseCode();
                if (responseCode != Requester.HTTP_STATUS_CODE_SUCCESS) {
                    String error = Requester.parseErrorStringAndDisconnect(connection);
                    Logger.printInfo(() -> "Playlist search failed with code: "
                            + responseCode + " error: " + error);
                    return null;
                }

                JSONObject json = Requester.parseJSONObject(connection);
                ParsePage pageResult = parsePage(json, query, seenVideoIds);
                results.addAll(pageResult.results);

                continuation = pageResult.continuation;
                if (continuation == null || continuation.isEmpty()) {
                    break;
                }
            }

            return new PlaylistSearchResponse(results);
        } catch (SocketTimeoutException ex) {
            Logger.printInfo(() -> "Connection timeout", ex);
        } catch (IOException ex) {
            Logger.printInfo(() -> "Network error", ex);
        } catch (Exception ex) {
            Logger.printException(() -> "fetch failed", ex);
        } finally {
            Logger.printDebug(() -> "Fetched playlist search, took: "
                    + (System.currentTimeMillis() - startTime) + "ms");
        }
        return null;
    }

    private static final class ParsePage {
        final List<PlaylistSearchResult> results;
        final String continuation;

        ParsePage(List<PlaylistSearchResult> results, String continuation) {
            this.results = results;
            this.continuation = continuation;
        }
    }

    private static ParsePage parsePage(
            JSONObject json,
            String query,
            Set<String> seenVideoIds
    ) {
        List<PlaylistSearchResult> results = new ArrayList<>();
        String continuation = null;

        try {
            JSONArray contents = findPlaylistContents(json);
            if (contents != null) {
                for (int i = 0; i < contents.length(); i++) {
                    JSONObject item = contents.optJSONObject(i);
                    if (item == null) {
                        continue;
                    }

                    JSONObject renderer = item.optJSONObject("playlistVideoRenderer");
                    if (renderer == null) {
                        continue;
                    }

                    String videoId = renderer.optString("videoId");
                    String setVideoId = renderer.optString("setVideoId");
                    String title = parseText(renderer.optJSONObject("title"));

                    if (videoId.isEmpty() || title.isEmpty() || !seenVideoIds.add(videoId)) {
                        continue;
                    }

                    if (title.toLowerCase(java.util.Locale.ROOT)
                            .contains(query.toLowerCase(java.util.Locale.ROOT))) {
                        results.add(new PlaylistSearchResult(
                                videoId,
                                setVideoId,
                                title,
                                parseMetadata(renderer),
                                parseThumbnail(renderer)
                        ));
                    }
                }
            }

            continuation = findContinuation(json);
        } catch (Exception ex) {
            Logger.printException(() -> "parsePage failed", ex);
        }

        return new ParsePage(results, continuation);
    }

    @Nullable
    private static JSONArray findPlaylistContents(JSONObject json) {
        try {
            JSONObject contents = json.optJSONObject("contents");
            if (contents == null) {
                return null;
            }

            JSONObject column = contents.optJSONObject("singleColumnBrowseResultsRenderer");
            if (column == null) {
                column = contents.optJSONObject("twoColumnBrowseResultsRenderer");
            }

            if (column != null) {
                JSONArray tabs = column.optJSONArray("tabs");
                if (tabs != null) {
                    for (int i = 0; i < tabs.length(); i++) {
                        JSONObject tab = tabs.optJSONObject(i);
                        if (tab == null) continue;
                        JSONObject tabRenderer = tab.optJSONObject("tabRenderer");
                        if (tabRenderer == null) continue;

                        JSONObject content = tabRenderer.optJSONObject("content");
                        if (content == null) continue;

                        JSONObject sectionList = content.optJSONObject("sectionListRenderer");
                        if (sectionList == null) continue;

                        JSONArray sectionContents = sectionList.optJSONArray("contents");
                        JSONArray found = findPlaylistContentsInSections(sectionContents);
                        if (found != null) return found;
                    }
                }
            }

            JSONObject continuationContents =
                    json.optJSONObject("continuationContents");
            if (continuationContents != null) {
                JSONObject list = continuationContents.optJSONObject(
                        "playlistVideoListContinuation"
                );
                if (list != null) {
                    return list.optJSONArray("contents");
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "findPlaylistContents failed", ex);
        }
        return null;
    }

    @Nullable
    private static JSONArray findPlaylistContentsInSections(@Nullable JSONArray sections) {
        if (sections == null) return null;

        for (int i = 0; i < sections.length(); i++) {
            JSONObject section = sections.optJSONObject(i);
            if (section == null) continue;

            JSONObject playlist = section.optJSONObject("playlistVideoListRenderer");
            if (playlist != null) {
                return playlist.optJSONArray("contents");
            }

            JSONObject itemSection = section.optJSONObject("itemSectionRenderer");
            if (itemSection != null) {
                JSONArray nested = findPlaylistContentsInSections(
                        itemSection.optJSONArray("contents")
                );
                if (nested != null) return nested;
            }
        }
        return null;
    }

    @Nullable
    private static String findContinuation(JSONObject json) {
        JSONObject continuation = findContinuationObject(json);
        if (continuation == null) {
            return null;
        }

        JSONObject continuationCommand =
                continuation.optJSONObject("continuationCommand");
        if (continuationCommand == null) {
            return null;
        }

        String token = continuationCommand.optString("token");
        return token.isEmpty() ? null : token;
    }

    @Nullable
    private static JSONObject findContinuationObject(JSONObject json) {
        try {
            JSONArray contents = findPlaylistContents(json);
            if (contents != null) {
                for (int i = 0; i < contents.length(); i++) {
                    JSONObject item = contents.optJSONObject(i);
                    if (item == null) continue;

                    JSONObject continuationItem =
                            item.optJSONObject("continuationItemRenderer");
                    if (continuationItem == null) continue;

                    JSONObject endpoint = continuationItem.optJSONObject("continuationEndpoint");
                    if (endpoint == null) continue;

                    JSONObject command = endpoint.optJSONObject("continuationCommand");
                    if (command != null && !command.optString("token").isEmpty()) {
                        JSONObject result = new JSONObject();
                        result.put("continuationCommand", command.optString("token"));
                        return result;
                    }
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "findContinuation failed", ex);
        }
        return null;
    }

    private static String parseMetadata(JSONObject renderer) {
        StringBuilder metadata = new StringBuilder();
        appendMetadata(metadata, parseText(renderer.optJSONObject("lengthText")));
        appendMetadata(metadata, parseText(renderer.optJSONObject("shortBylineText")));
        return metadata.toString();
    }

    private static void appendMetadata(StringBuilder metadata, String value) {
        if (value.isEmpty()) return;
        if (metadata.length() != 0) metadata.append("  •  ");
        metadata.append(value);
    }

    private static String parseText(@Nullable JSONObject text) {
        if (text == null) return "";

        String simpleText = text.optString("simpleText");
        if (!simpleText.isEmpty()) return simpleText;

        JSONArray runs = text.optJSONArray("runs");
        if (runs == null) return "";

        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < runs.length(); i++) {
            JSONObject run = runs.optJSONObject(i);
            if (run != null) builder.append(run.optString("text"));
        }
        return builder.toString();
    }

    private static String parseThumbnail(JSONObject video) {
        JSONObject thumbnail = video.optJSONObject("thumbnail");
        if (thumbnail == null) return "";

        JSONArray thumbnails = thumbnail.optJSONArray("thumbnails");
        if (thumbnails == null) return "";

        String best = "";
        for (int i = 0; i < thumbnails.length(); i++) {
            JSONObject entry = thumbnails.optJSONObject(i);
            if (entry != null && entry.optInt("width") <= 360) {
                best = entry.optString("url");
            }
        }
        return best;
    }
}
