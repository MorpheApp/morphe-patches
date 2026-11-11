/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3953
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.queue;

import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.requests.Requester;
import app.morphe.extension.shared.requests.Route;
import app.morphe.extension.youtube.patches.utils.requests.PlaylistRoutes;

/** Fetch the initial, finite batch of a dynamic Mix, without fetching player streams. */
final class MixPlaylistRequest {
    private static final String PLAYLIST = "contents.singleColumnWatchNextResults.playlist.playlist";
    private static final String MIX_PATH =
            "next?prettyPrint=false&fields=" + PLAYLIST + "(playlistId,contents.playlistPanelVideoRenderer"
                    + "(videoId,title,shortBylineText,longBylineText,videoInfo,lengthText,unplayableText))";

    private MixPlaylistRequest() {}

    @Nullable
    static List<VideoMetadata> fetch(String playlistId, String seedVideoId, Map<String, String> headers) {
        Utils.verifyOffMainThread();
        HttpURLConnection connection = null;
        try {
            byte[] body = PlaylistRoutes.getSetVideoIdBody(seedVideoId, playlistId);
            connection = PlaylistRoutes.getConnection(new Route(Route.Method.POST, MIX_PATH).compile(), headers);
            connection.setFixedLengthStreamingMode(body.length);
            try (var output = connection.getOutputStream()) {
                output.write(body);
            }
            if (connection.getResponseCode() == Requester.HTTP_STATUS_CODE_SUCCESS) {
                return parse(Requester.parseJSONObject(connection), playlistId);
            }
            Logger.printInfo(() -> "Native queue Mix request was unavailable");
        } catch (IOException exception) {
            Logger.printInfo(() -> "Could not fetch Mix for native queue", exception);
        } catch (Exception exception) {
            Logger.printException(() -> "Could not parse Mix for native queue", exception);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }

        return null;
    }

    @Nullable
    static List<VideoMetadata> parse(JSONObject response, String playlistId) {
        var contents = response.optJSONObject("contents");
        var next = contents == null ? null : contents.optJSONObject("singleColumnWatchNextResults");
        var wrapper = next == null ? null : next.optJSONObject("playlist");
        var playlist = wrapper == null ? null : wrapper.optJSONObject("playlist");
        if (playlist == null || !playlistId.equals(playlist.optString("playlistId"))) {
            return null;
        }
        var rows = playlist.optJSONArray("contents");
        if (rows == null) {
            return null;
        }
        List<VideoMetadata> result = new ArrayList<>();
        for (int index = 0; index < rows.length(); index++) {
            var item = rows.optJSONObject(index);
            var video = item == null ? null : item.optJSONObject("playlistPanelVideoRenderer");
            if (video == null || video.has("unplayableText")) {
                continue;
            }
            String id = video.optString("videoId");
            if (!PlaylistQueue.isVideoId(id)) {
                return null;
            }
            String channel = VideoMetadata.text(video.optJSONObject("longBylineText"));
            boolean fullByline = !channel.isEmpty();
            if (channel.isEmpty()) {
                channel = VideoMetadata.text(video.optJSONObject("shortBylineText"));
            }
            result.add(new VideoMetadata(id, VideoMetadata.text(video.optJSONObject("title")), channel,
                    VideoMetadata.text(video.optJSONObject("videoInfo")),
                    VideoMetadata.text(video.optJSONObject("lengthText")), fullByline, true));
        }

        // A Mix is generated dynamically. Append its initial batch, without
        // following its indefinite continuation or claiming to import a saved list.
        return result.isEmpty() ? null : List.copyOf(result);
    }
}
