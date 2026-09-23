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

    private static final int MAX_MILLISECONDS_TO_WAIT_FOR_FETCH = 60 * 1000;
    private static final int MAX_CONTINUATION_PAGES = 500;

    private static final Map<String, PlaylistSearchRequest> cache =
            Collections.synchronizedMap(Utils.createSizeRestrictedMap(10));

    private final Future<PlaylistSearchResponse> future;
    private final String cacheKey;

    private PlaylistSearchRequest(
            String cacheKey,
            String playlistId,
            String query,
            Map<String, String> requestHeader
    ) {
        this.cacheKey = cacheKey;
        future = Utils.submitOnBackgroundThread(() -> fetch(playlistId, query, requestHeader));
    }

    public static PlaylistSearchRequest fetchRequestIfNeeded(
            String playlistId,
            String query,
            Map<String, String> requestHeader
    ) {
        return cache.computeIfAbsent(
                playlistId + "\n" + query,
                key -> new PlaylistSearchRequest(key, playlistId, query, requestHeader)
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
            future.cancel(true);
            cache.remove(cacheKey, this);
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
            PlaylistSearchResponse nativeResponse = fetchNativeMetadata(
                    playlistId,
                    query,
                    requestHeader
            );

            if (nativeResponse != null) {
                Logger.printDebug(() -> "PLAYLIST_SEARCH native metadata path succeeded"
                        + " playlistId=" + playlistId
                        + " query=" + query
                        + " results=" + nativeResponse.results.size());
                return nativeResponse;
            }

            Logger.printDebug(() -> "PLAYLIST_SEARCH native metadata path failed"
                    + " playlistId=" + playlistId
                    + " query=" + query
                    + " falling back to browse pagination");

            String continuation = null;

            for (int page = 0; page < MAX_CONTINUATION_PAGES; page++) {
                final String requestContinuation = continuation;
                final int requestPage = page;
                final int seenBeforeRequest = seenVideoIds.size();
                final int accumulatedBeforeRequest = results.size();

                Logger.printDebug(() -> "PLAYLIST_SEARCH "
                        + "page=" + requestPage
                        + " REQUEST type="
                        + (requestContinuation == null
                        ? "INITIAL_BROWSE"
                        : "CONTINUATION")
                        + " playlistId=" + playlistId
                        + " query=" + query
                        + " seenBefore=" + seenBeforeRequest
                        + " accumulatedBefore=" + accumulatedBeforeRequest
                        + " continuationPresent="
                        + (requestContinuation != null)
                        + " continuationLength="
                        + (requestContinuation == null
                        ? 0
                        : requestContinuation.length()));

                if (requestContinuation != null) {
                    Logger.printDebug(() -> "PLAYLIST_SEARCH "
                            + "page=" + requestPage
                            + " continuationPrefix="
                            + requestContinuation.substring(
                                    0,
                                    Math.min(
                                            32,
                                            requestContinuation.length())));
                }

                byte[] requestBody = requestContinuation == null
                        ? PlaylistRoutes.browsePlaylistBody(playlistId)
                        : PlaylistRoutes.browsePlaylistContinuationBody(requestContinuation);

                Logger.printDebug(() -> "PLAYLIST_SEARCH "
                        + "page=" + requestPage
                        + " requestBodyBytes=" + requestBody.length);

                HttpURLConnection connection = PlaylistRoutes.getConnection(
                        PlaylistRoutes.BROWSE_PLAYLIST,
                        requestHeader
                );

                Logger.printDebug(() -> "PLAYLIST_SEARCH "
                        + "page=" + requestPage
                        + " connectionCreated"
                        + " url=" + connection.getURL()
                        + " method=" + connection.getRequestMethod());

                connection.setFixedLengthStreamingMode(requestBody.length);
                connection.getOutputStream().write(requestBody);

                Logger.printDebug(() -> "PLAYLIST_SEARCH "
                        + "page=" + requestPage
                        + " requestBodySent");

                final int responseCode = connection.getResponseCode();

                Logger.printDebug(() -> "PLAYLIST_SEARCH "
                        + "page=" + requestPage
                        + " HTTP responseCode=" + responseCode
                        + " contentType=" + connection.getContentType()
                        + " contentLength=" + connection.getContentLengthLong());

                if (responseCode != Requester.HTTP_STATUS_CODE_SUCCESS) {
                    String error =
                            Requester.parseErrorStringAndDisconnect(connection);

                    Logger.printInfo(() -> "PLAYLIST_SEARCH "
                            + "page=" + requestPage
                            + " HTTP_FAILURE"
                            + " code=" + responseCode
                            + " error=" + error);

                    return null;
                }

                JSONObject json = Requester.parseJSONObject(connection);

                Logger.printDebug(() -> "PLAYLIST_SEARCH "
                        + "page=" + requestPage
                        + " JSON parsed"
                        + " topLevelKeys=" + json.names()
                        + " hasContents=" + json.has("contents")
                        + " hasContinuationContents="
                        + json.has("continuationContents")
                        + " hasActions="
                        + json.has("onResponseReceivedActions")
                        + " hasEndpoints="
                        + json.has("onResponseReceivedEndpoints")
                        + " hasCommands="
                        + json.has("onResponseReceivedCommands"));

                final boolean continuationPage = continuation != null;

                ParsePage pageResult = parsePage(
                        json,
                        query,
                        seenVideoIds,
                        continuationPage
                );

                Logger.printDebug(() -> "PLAYLIST_SEARCH "
                        + "page=" + requestPage
                        + " PARSE_RESULT"
                        + " continuationPage=" + continuationPage
                        + " matchedResults=" + pageResult.results.size()
                        + " seenAfter=" + seenVideoIds.size()
                        + " continuationPresent="
                        + (pageResult.continuation != null)
                        + " continuationLength="
                        + (pageResult.continuation == null
                        ? 0
                        : pageResult.continuation.length()));

                if (pageResult.continuation != null) {
                    Logger.printDebug(() -> "PLAYLIST_SEARCH "
                            + "page=" + requestPage
                            + " returnedContinuationPrefix="
                            + pageResult.continuation.substring(
                                    0,
                                    Math.min(
                                            32,
                                            pageResult.continuation.length()
                                    )));
                }

                results.addAll(pageResult.results);

                Logger.printDebug(() -> "PLAYLIST_SEARCH "
                        + "page=" + requestPage
                        + " ACCUMULATED"
                        + " totalResults=" + results.size()
                        + " seenVideoIds=" + seenVideoIds.size());

                continuation = pageResult.continuation;

                if (continuation == null || continuation.isEmpty()) {
                    Logger.printDebug(() -> "PLAYLIST_SEARCH "
                            + "page=" + requestPage
                            + " STOP reason=NO_CONTINUATION");
                    break;
                }

                Logger.printDebug(() -> "PLAYLIST_SEARCH "
                        + "page=" + requestPage
                        + " CONTINUE nextPage=" + (requestPage + 1));
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

    @Nullable
    private static PlaylistSearchResponse fetchNativeMetadata(
            String playlistId,
            String query,
            Map<String, String> requestHeader
    ) {
        Utils.verifyOffMainThread();

        try {
            byte[] requestBody =
                    PlaylistRoutes.playlistFilterSearchMetadataBody(playlistId);

            Logger.printDebug(() -> "PLAYLIST_SEARCH native REQUEST"
                    + " playlistId=" + playlistId
                    + " query=" + query
                    + " requestBodyBytes=" + requestBody.length);

            HttpURLConnection connection = PlaylistRoutes.getConnection(
                    PlaylistRoutes.PLAYLIST_FILTER_SEARCH_METADATA,
                    requestHeader
            );

            connection.setFixedLengthStreamingMode(requestBody.length);
            connection.getOutputStream().write(requestBody);

            final int responseCode = connection.getResponseCode();

            Logger.printDebug(() -> "PLAYLIST_SEARCH native HTTP"
                    + " responseCode=" + responseCode
                    + " contentType=" + connection.getContentType()
                    + " contentLength=" + connection.getContentLengthLong());

            if (responseCode != Requester.HTTP_STATUS_CODE_SUCCESS) {
                String error =
                        Requester.parseErrorStringAndDisconnect(connection);

                Logger.printInfo(() -> "PLAYLIST_SEARCH native HTTP_FAILURE"
                        + " code=" + responseCode
                        + " error=" + error);

                return null;
            }

            JSONObject json = Requester.parseJSONObject(connection);
            JSONArray tracks = json.optJSONArray("tracks");

            if (tracks == null) {
                Logger.printInfo(() -> "PLAYLIST_SEARCH native response has no tracks array"
                        + " topLevelKeys=" + json.names());
                return null;
            }

            final String normalizedQuery = query.trim().toLowerCase(
                    java.util.Locale.ROOT
            );
            final List<PlaylistSearchResult> results = new ArrayList<>();

            for (int i = 0; i < tracks.length(); i++) {
                JSONObject track = tracks.optJSONObject(i);
                if (track == null) {
                    continue;
                }

                String videoId = track.optString("videoId");
                String setVideoId = track.optString("setVideoId");
                String title = track.optString("trackName");
                String albumName = track.optString("albumName");

                if (videoId.isEmpty() || title.isEmpty()) {
                    continue;
                }

                JSONArray artistNames = track.optJSONArray("artistNames");
                StringBuilder artists = new StringBuilder();

                if (artistNames != null) {
                    for (int j = 0; j < artistNames.length(); j++) {
                        String artist = artistNames.optString(j);
                        if (artist.isEmpty()) {
                            continue;
                        }

                        if (artists.length() > 0) {
                            artists.append(", ");
                        }
                        artists.append(artist);
                    }
                }

                String metadata = artists.toString();
                if (!albumName.isEmpty()) {
                    if (!metadata.isEmpty()) {
                        metadata += " • ";
                    }
                    metadata += albumName;
                }

                String searchableText = title + " " + metadata;
                if (!queryMatches(searchableText, normalizedQuery)) {
                    continue;
                }

                results.add(new PlaylistSearchResult(
                        videoId,
                        setVideoId,
                        title,
                        metadata,
                        "https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg"
                ));
            }

            Logger.printDebug(() -> "PLAYLIST_SEARCH native PARSED"
                    + " tracks=" + tracks.length()
                    + " matchedResults=" + results.size());

            return new PlaylistSearchResponse(results);
        } catch (SocketTimeoutException ex) {
            Logger.printInfo(() -> "PLAYLIST_SEARCH native timeout", ex);
        } catch (IOException ex) {
            Logger.printInfo(() -> "PLAYLIST_SEARCH native network error", ex);
        } catch (Exception ex) {
            Logger.printException(
                    () -> "PLAYLIST_SEARCH native parse failure",
                    ex
            );
        }

        return null;
    }

    private static boolean queryMatches(String searchableText, String normalizedQuery) {
        if (normalizedQuery.isEmpty()) {
            return true;
        }

        String normalizedText = searchableText.toLowerCase(java.util.Locale.ROOT);

        for (String token : normalizedQuery.split("\\s+")) {
            if (!token.isEmpty() && !normalizedText.contains(token)) {
                return false;
            }
        }

        return true;
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
            Set<String> seenVideoIds,
            boolean continuationPage
    ) {
        List<PlaylistSearchResult> results = new ArrayList<>();
        String continuation = null;

        Logger.printDebug(() -> "PLAYLIST_SEARCH parsePage START"
                + " continuationPage=" + continuationPage
                + " query=" + query
                + " seenBefore=" + seenVideoIds.size()
                + " topLevelKeys=" + json.names());

        try {
            List<JSONArray> contentArrays = continuationPage
                    ? findContinuationItems(json)
                    : findInitialPlaylistContents(json);

            int totalItems = 0;
            for (JSONArray array : contentArrays) {
                totalItems += array.length();
            }

            final int parserArrayCount = contentArrays.size();
            final int parserItemCount = totalItems;

            Logger.printDebug(() -> "PLAYLIST_SEARCH parsePage CONTENTS"
                    + " continuationPage=" + continuationPage
                    + " arrays=" + parserArrayCount
                    + " items=" + parserItemCount);

            for (int arrayIndex = 0;
                    arrayIndex < contentArrays.size();
                    arrayIndex++) {

                JSONArray contents = contentArrays.get(arrayIndex);

                final int currentArrayIndex = arrayIndex;
                Logger.printDebug(() -> "PLAYLIST_SEARCH parsePage ARRAY"
                        + " index=" + currentArrayIndex
                        + " length=" + contents.length());
                for (int i = 0; i < contents.length(); i++) {
                    JSONObject item = contents.optJSONObject(i);
                    if (item == null) {
                        continue;
                    }

                    JSONObject renderer =
                            item.optJSONObject("playlistVideoRenderer");
                    if (renderer == null) {
                        continue;
                    }

                    String videoId = renderer.optString("videoId");
                    String setVideoId = renderer.optString("setVideoId");
                    String title =
                            parseText(renderer.optJSONObject("title"));

                    if (videoId.isEmpty()
                            || title.isEmpty()
                            || !seenVideoIds.add(videoId)) {
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

                String next = findNextContinuation(contents);

                final int arrayLength = contents.length();
                final String nextPrefix = next == null
                        ? "null"
                        : next.substring(0, Math.min(32, next.length()));

                Logger.printDebug(() -> "PLAYLIST_SEARCH parsePage ARRAY_DONE"
                        + " length=" + arrayLength
                        + " nextContinuation="
                        + (next != null)
                        + " nextLength="
                        + (next == null ? 0 : next.length())
                        + " nextPrefix=" + nextPrefix);

                if (next != null && !next.isEmpty()) {
                    continuation = next;
                }
            }

            if (continuation == null || continuation.isEmpty()) {
                Logger.printDebug(() -> "PLAYLIST_SEARCH parsePage "
                        + "STRUCTURED_CONTINUATION_MISSING "
                        + "usingRecursiveJSONFallback");

                continuation = findNextContinuation(json);

                final String fallbackPrefix = continuation == null
                        ? "null"
                        : continuation.substring(
                                0,
                                Math.min(32, continuation.length()));

                final String fallbackContinuation = continuation;

                Logger.printDebug(() -> "PLAYLIST_SEARCH parsePage "
                        + "RECURSIVE_FALLBACK_RESULT"
                        + " found=" + (fallbackContinuation != null)
                        + " length="
                        + (fallbackContinuation == null ? 0 : fallbackContinuation.length())
                        + " prefix=" + fallbackPrefix);
            } else {
                Logger.printDebug(() -> "PLAYLIST_SEARCH parsePage "
                        + "STRUCTURED_CONTINUATION_FOUND");
            }
        } catch (Exception ex) {
            Logger.printException(
                    () -> "PLAYLIST_SEARCH parsePage EXCEPTION",
                    ex
            );
        }

        final int finalResultCount = results.size();
        final int finalSeenCount = seenVideoIds.size();
        final String finalContinuation = continuation;

        Logger.printDebug(() -> "PLAYLIST_SEARCH parsePage END"
                + " matchedResults=" + finalResultCount
                + " seen=" + finalSeenCount
                + " continuationPresent="
                + (finalContinuation != null)
                + " continuationLength="
                + (finalContinuation == null
                ? 0
                : finalContinuation.length()));

        return new ParsePage(results, continuation);
    }

    private static List<JSONArray> findInitialPlaylistContents(JSONObject json) {
        List<JSONArray> result = new ArrayList<>();

        try {
            JSONObject contents = json.optJSONObject("contents");

            Logger.printDebug(() -> "PLAYLIST_SEARCH INITIAL"
                    + " hasContents=" + (contents != null));

            if (contents == null) {
                return result;
            }

            JSONObject column =
                    contents.optJSONObject("singleColumnBrowseResultsRenderer");

            if (column == null) {
                column =
                        contents.optJSONObject("twoColumnBrowseResultsRenderer");
            }

            if (column == null) {
                return result;
            }

            JSONArray tabs = column.optJSONArray("tabs");
            if (tabs == null) {
                return result;
            }

            for (int i = 0; i < tabs.length(); i++) {
                JSONObject tab = tabs.optJSONObject(i);
                if (tab == null) {
                    continue;
                }

                JSONObject tabRenderer = tab.optJSONObject("tabRenderer");
                if (tabRenderer == null) {
                    continue;
                }

                JSONObject content =
                        tabRenderer.optJSONObject("content");
                if (content == null) {
                    continue;
                }

                JSONObject sectionList =
                        content.optJSONObject("sectionListRenderer");
                if (sectionList == null) {
                    continue;
                }

                collectPlaylistContents(
                        sectionList.optJSONArray("contents"),
                        result
                );
            }
        } catch (Exception ex) {
            Logger.printException(
                    () -> "findInitialPlaylistContents failed",
                    ex
            );
        }

        return result;
    }

    private static void collectPlaylistContents(
            @Nullable JSONArray sections,
            List<JSONArray> result
    ) {
        if (sections == null) {
            return;
        }

        for (int i = 0; i < sections.length(); i++) {
            JSONObject section = sections.optJSONObject(i);
            if (section == null) {
                continue;
            }

            JSONObject playlist =
                    section.optJSONObject("playlistVideoListRenderer");

            if (playlist != null) {
                JSONArray contents =
                        playlist.optJSONArray("contents");

                if (contents != null) {
                    result.add(contents);
                }

                continue;
            }

            JSONObject itemSection =
                    section.optJSONObject("itemSectionRenderer");

            if (itemSection != null) {
                collectPlaylistContents(
                        itemSection.optJSONArray("contents"),
                        result
                );
            }
        }
    }

    private static List<JSONArray> findContinuationItems(JSONObject json) {
        List<JSONArray> result = new ArrayList<>();

        try {
            JSONArray actions =
                    json.optJSONArray("onResponseReceivedActions");

            JSONArray endpoints =
                    json.optJSONArray("onResponseReceivedEndpoints");

            JSONArray commands =
                    json.optJSONArray("onResponseReceivedCommands");

            JSONObject continuationContents =
                    json.optJSONObject("continuationContents");

            Logger.printDebug(() -> "PLAYLIST_SEARCH CONTINUATION_STRUCTURE"
                    + " actions="
                    + (actions == null ? 0 : actions.length())
                    + " endpoints="
                    + (endpoints == null ? 0 : endpoints.length())
                    + " commands="
                    + (commands == null ? 0 : commands.length())
                    + " continuationContents="
                    + (continuationContents != null));

            collectContinuationItems(
                    actions,
                    result
            );

            collectContinuationItems(
                    json.optJSONArray("onResponseReceivedEndpoints"),
                    result
            );

            collectContinuationItems(
                    json.optJSONArray("onResponseReceivedCommands"),
                    result
            );

            if (continuationContents != null) {
                JSONObject playlist =
                        continuationContents.optJSONObject(
                                "playlistVideoListContinuation"
                        );

                if (playlist != null) {
                    JSONArray contents =
                            playlist.optJSONArray("contents");

                    if (contents != null) {
                        result.add(contents);
                    }
                }
            }
        } catch (Exception ex) {
            Logger.printException(
                    () -> "findContinuationItems failed",
                    ex
            );
        }

        return result;
    }

    private static void collectContinuationItems(
            @Nullable JSONArray actions,
            List<JSONArray> result
    ) {
        if (actions == null) {
            return;
        }

        for (int i = 0; i < actions.length(); i++) {
            JSONObject action = actions.optJSONObject(i);
            if (action == null) {
                continue;
            }

            JSONObject append =
                    action.optJSONObject("appendContinuationItemsAction");

            JSONObject reload =
                    action.optJSONObject("reloadContinuationItemsCommand");

            final int actionIndex = i;

            Logger.printDebug(() -> "PLAYLIST_SEARCH CONTINUATION_ACTION"
                    + " index=" + actionIndex
                    + " hasAppend=" + (append != null)
                    + " hasReload=" + (reload != null)
                    + " keys=" + action.names());

            if (append != null) {
                JSONArray items =
                        append.optJSONArray("continuationItems");

                if (items != null) {
                    result.add(items);
                }
            }

            if (reload != null) {
                JSONArray items =
                        reload.optJSONArray("continuationItems");

                if (items != null) {
                    result.add(items);
                }
            }
        }
    }

    @Nullable
    private static String findNextContinuation(JSONArray contents) {
        if (contents == null) {
            return null;
        }

        for (int i = contents.length() - 1; i >= 0; i--) {
            final int tokenItemIndex = i;
            JSONObject item = contents.optJSONObject(i);
            if (item == null) {
                continue;
            }

            JSONObject continuationItem =
                    item.optJSONObject("continuationItemRenderer");

            if (continuationItem == null) {
                continue;
            }

            Logger.printDebug(() -> "PLAYLIST_SEARCH TOKEN_CANDIDATE"
                    + " itemIndex=" + tokenItemIndex
                    + " continuationItemKeys="
                    + continuationItem.names());

            JSONObject endpoint =
                    continuationItem.optJSONObject("continuationEndpoint");

            if (endpoint == null) {
                continue;
            }

            JSONObject command =
                    endpoint.optJSONObject("continuationCommand");

            if (command != null) {
                String token = command.optString("token");
                if (!token.isEmpty()) {
                    Logger.printDebug(() -> "PLAYLIST_SEARCH TOKEN_FOUND"
                            + " source=nextContinuationData.continuation"
                            + " length=" + token.length());
                    return token;
                }
            }

            JSONObject next =
                    endpoint.optJSONObject("nextContinuationData");

            if (next != null) {
                String token = next.optString("continuation");
                if (!token.isEmpty()) {
                    Logger.printDebug(() -> "PLAYLIST_SEARCH TOKEN_FOUND"
                            + " source=reloadContinuationData.continuation"
                            + " length=" + token.length());
                    return token;
                }
            }

            JSONObject reload =
                    endpoint.optJSONObject("reloadContinuationData");

            if (reload != null) {
                String token = reload.optString("continuation");
                if (!token.isEmpty()) {
                    return token;
                }
            }
        }

        return null;
    }

    @Nullable
    private static String findNextContinuation(JSONObject json) {
        return findContinuationToken(json);
    }

    @Nullable
    private static String findContinuationToken(Object value) {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;

            JSONObject continuationItem =
                    object.optJSONObject("continuationItemRenderer");

            if (continuationItem != null) {
                JSONObject endpoint =
                        continuationItem.optJSONObject(
                                "continuationEndpoint");

                if (endpoint != null) {
                    JSONObject command =
                            endpoint.optJSONObject("continuationCommand");

                    if (command != null) {
                        String token = command.optString("token");
                        if (!token.isEmpty()) {
                            return token;
                        }
                    }

                    JSONObject next =
                            endpoint.optJSONObject("nextContinuationData");

                    if (next != null) {
                        String token =
                                next.optString("continuation");

                        if (!token.isEmpty()) {
                            return token;
                        }
                    }

                    JSONObject reload =
                            endpoint.optJSONObject(
                                    "reloadContinuationData");

                    if (reload != null) {
                        String token =
                                reload.optString("continuation");

                        if (!token.isEmpty()) {
                            return token;
                        }
                    }
                }
            }

            JSONArray names = object.names();

            if (names != null) {
                for (int i = names.length() - 1; i >= 0; i--) {
                    String name = names.optString(i);

                    if (name.isEmpty()) {
                        continue;
                    }

                    String token =
                            findContinuationToken(object.opt(name));

                    if (token != null && !token.isEmpty()) {
                        return token;
                    }
                }
            }
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;

            for (int i = array.length() - 1; i >= 0; i--) {
                String token =
                        findContinuationToken(array.opt(i));

                if (token != null && !token.isEmpty()) {
                    return token;
                }
            }
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
