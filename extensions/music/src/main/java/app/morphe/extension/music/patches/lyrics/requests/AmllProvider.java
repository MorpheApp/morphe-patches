/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2625
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.requests;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.HttpURLConnection;

import app.morphe.extension.music.patches.lyrics.TrackInfo;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.requests.Requester;

public final class AmllProvider implements LyricsProvider {

    private static final String BASE_URL = "https://api.amll.dev/v1/lyrics";

    @Override
    public String name() {
        return "AMLL";
    }

    @Nullable
    @Override
    public FetchResult fetch(TrackInfo track) throws Exception {
        if (track.title().isEmpty() || track.artist().isEmpty()) {
            return null;
        }

        final JSONObject best = searchBest(track.title(), track.artist(), track.album());
        if (best == null) {
            return null;
        }
        final long lyricId = best.optLong("id", -1);
        if (lyricId < 0) {
            return null;
        }

        HttpURLConnection getConnection = null;
        try {
            getConnection = LyricsRequests.openConnection(
                    BASE_URL + "/get?id=" + lyricId + "&format=ttml");
            if (getConnection.getResponseCode() != Requester.HTTP_STATUS_CODE_SUCCESS) {
                LyricsRequests.logFailure(name(), getConnection);
                return null;
            }
            JSONObject getRoot = Requester.parseJSONObject(getConnection);
            JSONObject getData = getRoot.optJSONObject("data");
            if (getData == null) {
                return null;
            }
            String ttml = LyricsRequests.optString(getData, "lyrics");
            if (ttml == null) {
                return null;
            }
            return FetchResult.blind(TtmlParser.ttmlToLyrics(ttml, name(), sourceUrl(getData, best)));
        } finally {
            if (getConnection != null) {
                getConnection.disconnect();
            }
        }
    }

    @Nullable
    private static String sourceUrl(JSONObject getData, JSONObject best) {
        String file = firstPath(getData, "path", "file", "filename", "filePath");
        if (file == null) {
            file = firstPath(best, "path", "file", "filename", "filePath");
        }
        if (file == null || file.isEmpty()) {
            return null;
        }
        return "https://amlldb.bikonoo.com/view-lyric.html?file=" + file;
    }

    @Nullable
    private static String firstPath(JSONObject object, String... keys) {
        for (String key : keys) {
            final String value = LyricsRequests.optString(object, key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    @Nullable
    private JSONObject searchBest(String title, String artist, String album) {
        HttpURLConnection searchConnection = null;
        try {
            String searchUrl = String.format(
                    "%s/search?musicName=%s&artistName=%s",
                    BASE_URL,
                    LyricsRequests.encode(title),
                    LyricsRequests.encode(artist)
            );

            if (album != null && !album.isEmpty()) {
                searchUrl += "&albumName=" + LyricsRequests.encode(album);
            }

            searchConnection = LyricsRequests.openConnection(searchUrl);
            if (searchConnection.getResponseCode() != Requester.HTTP_STATUS_CODE_SUCCESS) {
                return null;
            }
            JSONObject searchRoot = Requester.parseJSONObject(searchConnection);
            JSONObject searchData = searchRoot.optJSONObject("data");
            if (searchData == null) {
                return null;
            }
            JSONArray items = searchData.optJSONArray("items");
            if (items == null || items.length() == 0) {
                return null;
            }
            JSONObject best = items.optJSONObject(0);
            if (best == null || best.optLong("id", -1) < 0) {
                return null;
            }
            return best;
        } catch (Exception ex) {
            Logger.printInfo(() -> "Could not fetch lyrics", ex);
            return null;
        } finally {
            if (searchConnection != null) {
                searchConnection.disconnect();
            }
        }
    }
}
