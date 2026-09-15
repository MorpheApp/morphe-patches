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

import app.morphe.extension.music.patches.lyrics.Lyrics;
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
    public Lyrics fetch(TrackInfo track) throws Exception {
        if (track.title().isEmpty() || track.artist().isEmpty()) {
            return null;
        }

        // 1) Resolve the AMLL lyric id from the track metadata.
        final long lyricId = searchLyricId(track.title(), track.artist(), track.album());

        if (lyricId < 0) {
            return null;
        }

        // word-level timing; the LyricsManager falls back to line sync if a line lacks words.
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
            return TtmlParser.ttmlToLyrics(ttml, name(), null);
        } finally {
            if (getConnection != null) {
                getConnection.disconnect();
            }
        }
    }

    private long searchLyricId(String title, String artist, String album) {
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
                return -1;
            }
            JSONObject searchRoot = Requester.parseJSONObject(searchConnection);
            JSONObject searchData = searchRoot.optJSONObject("data");
            if (searchData == null) {
                return -1;
            }
            JSONArray items = searchData.optJSONArray("items");
            if (items == null || items.length() == 0) {
                return -1;
            }
            JSONObject best = items.optJSONObject(0);
            if (best == null) {
                return -1;
            }
            return best.optLong("id", -1);
        } catch (Exception ex) {
            Logger.printInfo(() -> "Could not fetch lyrics", ex);
            return -1;
        } finally {
            if (searchConnection != null) {
                searchConnection.disconnect();
            }
        }
    }
}
