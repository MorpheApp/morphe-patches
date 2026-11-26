/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5200
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches.utils.requests;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.patches.utils.requests.ChannelSearchRequest.ChannelSearchResult;

/**
 * Searches a playlist by listing all of its videos and keeping those whose title or channel
 * has every word of the search. Playlists have no search of their own.
 * <p>
 * The playlist is listed as the signed-in user, so private playlists and Watch later can be searched.
 */
public final class PlaylistSearchRequest {

    public static final class Results {
        /** Title of the playlist, or empty if it is not known. */
        public final String playlistTitle;
        /** Matching videos, in the order of the playlist. */
        public final List<ChannelSearchResult> matches;

        private Results(String playlistTitle, List<ChannelSearchResult> matches) {
            this.playlistTitle = playlistTitle;
            this.matches = matches;
        }
    }

    public interface Callback {
        /**
         * Called on a background thread.
         *
         * @param results    The matches found so far, or null if the playlist could not be listed.
         * @param isComplete Whether the search is done. Pages that could not be listed after the
         *                   first page are left out.
         */
        void onResults(@Nullable Results results, boolean isComplete);
    }

    /**
     * @param searchText Title and channel of the video, which a search matches.
     */
    private record Video(ChannelSearchResult result, String searchText) {
    }

    private static final class ListedPlaylist {
        final String title;
        final List<Video> videos;
        final Map<String, String> requestHeader;
        final long listedTime;

        ListedPlaylist(String title, List<Video> videos, Map<String, String> requestHeader) {
            this.title = title;
            this.videos = videos;
            this.requestHeader = requestHeader;
            this.listedTime = System.currentTimeMillis();
        }
    }

    /**
     * A listed playlist is searched again without listing it again for a while,
     * as searching again with other words is common.
     */
    private static final long CACHE_MILLISECONDS = 5 * 60 * 1000;

    private static final Map<String, ListedPlaylist> cache = Collections.synchronizedMap(
            Utils.createSizeRestrictedMap(5));

    private PlaylistSearchRequest() {
    }

    /**
     * Calls back after each page that has more matching videos, and once more when done.
     */
    public static void fetch(String playlistId, String query, Map<String, String> requestHeader,
                             BooleanSupplier isCancelled, Callback callback) {
        Utils.runOnBackgroundThread(() -> {
            try {
                List<String> queryWords = ChannelUploadsRequest.normalizedWords(query);

                ListedPlaylist cached = cache.get(playlistId);
                if (cached != null && cached.requestHeader.equals(requestHeader)
                        && System.currentTimeMillis() - cached.listedTime < CACHE_MILLISECONDS) {
                    callback.onResults(new Results(cached.title, matches(cached.videos, queryWords)), true);
                    return;
                }

                listPlaylist(playlistId, queryWords, requestHeader, isCancelled, callback);
            } catch (IOException ex) {
                Logger.printInfo(() -> "Could not list playlist: " + playlistId, ex);
                callback.onResults(null, true);
            } catch (Exception ex) {
                Logger.printException(() -> "fetch failure", ex);
                callback.onResults(null, true);
            }
        });
    }

    private static void listPlaylist(String playlistId, List<String> queryWords, Map<String, String> requestHeader,
                                     BooleanSupplier isCancelled, Callback callback) throws IOException, JSONException {
        final long startTime = System.currentTimeMillis();
        List<Video> videos = new ArrayList<>();
        List<ChannelSearchResult> matches = new ArrayList<>();
        String[] title = {""};

        GetPlaylistItemsRequest.PagesResult pagesResult = GetPlaylistItemsRequest.fetchPages(
                PlaylistRoutes.BROWSE_PLAYLIST_SEARCH_VIDEOS, playlistId, requestHeader, (response, page) -> {
                    if (isCancelled.getAsBoolean()) {
                        return false;
                    }
                    if (title[0].isEmpty()) {
                        // Only the response of the first page has the header.
                        title[0] = parsePlaylistTitle(response);
                    }

                    List<Video> pageVideos = parsePage(page);
                    videos.addAll(pageVideos);
                    List<ChannelSearchResult> pageMatches = matches(pageVideos, queryWords);
                    if (!pageMatches.isEmpty()) {
                        matches.addAll(pageMatches);
                        callback.onResults(new Results(title[0], new ArrayList<>(matches)), false);
                    }
                    return true;
                });

        Logger.printDebug(() -> "Listed " + videos.size() + " videos of playlist: " + playlistId
                + " result: " + pagesResult + " took: " + (System.currentTimeMillis() - startTime) + "ms");
        if (isCancelled.getAsBoolean()) {
            return;
        }
        if (pagesResult == GetPlaylistItemsRequest.PagesResult.FAILED) {
            callback.onResults(null, true);
            return;
        }
        if (pagesResult == GetPlaylistItemsRequest.PagesResult.COMPLETE) {
            // A playlist missing pages is not kept, so the next search lists it again.
            cache.put(playlistId, new ListedPlaylist(title[0], videos, requestHeader));
        }
        callback.onResults(new Results(title[0], matches), true);
    }

    private static List<ChannelSearchResult> matches(List<Video> videos, List<String> queryWords) {
        List<ChannelSearchResult> matches = new ArrayList<>();
        for (Video video : videos) {
            if (ChannelUploadsRequest.matches(video.searchText, queryWords)) {
                matches.add(video.result);
            }
        }
        return matches;
    }

    private static String parsePlaylistTitle(JSONObject response) {
        JSONObject header = response.optJSONObject("header");
        JSONObject pageHeader = header == null ? null : header.optJSONObject("pageHeaderRenderer");
        return pageHeader == null ? "" : pageHeader.optString("pageTitle");
    }

    private static List<Video> parsePage(JSONObject page) {
        JSONArray contents = page.optJSONArray("contents");
        if (contents == null) {
            return Collections.emptyList();
        }

        List<Video> videos = new ArrayList<>(contents.length());
        for (int i = 0, length = contents.length(); i < length; i++) {
            JSONObject element = contents.optJSONObject(i);
            JSONObject renderer = element == null ? null : element.optJSONObject("playlistVideoRenderer");
            if (renderer == null) continue;

            String videoId = renderer.optString("videoId");
            String title = ChannelSearchRequest.parseText(renderer.optJSONObject("title"));
            if (videoId.isEmpty() || title.isEmpty()) continue;

            String channelName = ChannelSearchRequest.parseText(renderer.optJSONObject("shortBylineText"));
            List<String> details = new ArrayList<>();
            if (!channelName.isEmpty()) {
                details.add(channelName);
            }
            details.addAll(parseVideoInfo(renderer.optJSONObject("videoInfo")));

            JSONObject thumbnail = renderer.optJSONObject("thumbnail");
            ChannelSearchResult result = ChannelSearchRequest.createResult(videoId, title,
                    thumbnail == null ? "" : ChannelSearchRequest.widestThumbnail(thumbnail.optJSONArray("thumbnails")),
                    ChannelSearchRequest.parseText(renderer.optJSONObject("lengthText")),
                    details);
            videos.add(new Video(result, title + "\n" + channelName));
        }
        return videos;
    }

    /**
     * @return The view count and the age of the video, which are separate runs
     *         with a separator run between them.
     */
    private static List<String> parseVideoInfo(@Nullable JSONObject videoInfo) {
        List<String> details = new ArrayList<>();
        JSONArray runs = videoInfo == null ? null : videoInfo.optJSONArray("runs");
        if (runs == null) {
            return details;
        }

        for (int i = 0, length = runs.length(); i < length; i++) {
            JSONObject run = runs.optJSONObject(i);
            String text = run == null ? "" : run.optString("text").trim();
            if (!text.isEmpty() && !"•".equals(text)) {
                details.add(text);
            }
        }
        return details;
    }
}
