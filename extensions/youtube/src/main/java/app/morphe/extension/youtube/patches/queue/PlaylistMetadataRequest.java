/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3953
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.queue;

import androidx.annotation.Nullable;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.Locale;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.requests.Requester;
import app.morphe.extension.shared.requests.Route;
import app.morphe.extension.youtube.patches.utils.requests.ChannelIdRoutes;

/** Completes missing display data; never supplies playback or account actions. */
final class PlaylistMetadataRequest {
    private static final String VIDEO_FIELD =
            "contents.sectionListRenderer.contents.itemSectionRenderer.contents.compactVideoRenderer";
    private static final Route.CompiledRoute SEARCH = new Route(Route.Method.POST,
            "search?prettyPrint=false&fields=" + VIDEO_FIELD + ".videoId,"
                    + VIDEO_FIELD + ".title.runs.text,"
                    + VIDEO_FIELD + ".longBylineText.runs.text,"
                    + VIDEO_FIELD + ".shortBylineText.runs.text,"
                    + VIDEO_FIELD + ".shortViewCountText.runs.text,"
                    + VIDEO_FIELD + ".publishedTimeText.runs.text,"
                    + VIDEO_FIELD + ".lengthText.runs.text").compile();

    private PlaylistMetadataRequest() {}

    @Nullable
    static VideoMetadata fetch(String videoId, String title, Locale locale) {
        Utils.verifyOffMainThread();
        HttpURLConnection connection = null;
        try {
            // Titles are searchable even when a newly uploaded video's ID is not indexed.
            // The response parser still requires an exact video ID, never the first result.
            String query = title.isEmpty() || title.equals(videoId)
                    ? "https://www.youtube.com/watch?v=" + videoId : title;
            byte[] body = ChannelIdRoutes.createSearchBody(query);
            connection = ChannelIdRoutes.getConnection(SEARCH);
            connection.setFixedLengthStreamingMode(body.length);
            try (var output = connection.getOutputStream()) {
                output.write(body);
            }

            if (connection.getResponseCode() == Requester.HTTP_STATUS_CODE_SUCCESS) {
                VideoMetadata result = VideoMetadata.fromSearch(Requester.parseJSONObject(connection), videoId);
                return Locale.getDefault().equals(locale) ? result : null;
            }

            Logger.printInfo(() -> "Native playlist card lookup was unavailable");
        } catch (IOException exception) {
            Logger.printInfo(() -> "Could not complete native playlist card metadata", exception);
        } catch (Exception exception) {
            Logger.printException(() -> "Could not parse native playlist card metadata", exception);
        } finally {
            if (connection != null) connection.disconnect();
        }

        return null;
    }
}
