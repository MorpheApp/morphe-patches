/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2269
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.requests;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;

import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.util.List;

import app.morphe.extension.music.patches.lyrics.Lyrics;
import app.morphe.extension.music.patches.lyrics.TrackInfo;
import app.morphe.extension.shared.requests.Requester;

public final class BinimumProvider implements LyricsProvider {

    private static final String BASE_URL = "https://lyrics-api.binimum.org/";

    @Override
    public String name() {
        return "BiniLyrics";
    }

    @Nullable
    @Override
    public Lyrics fetch(TrackInfo track) throws Exception {
        final String lyricsUrl = resolveLyricsUrl(track);
        if (lyricsUrl == null) {
            return null;
        }

        HttpURLConnection connection = null;
        try {
            connection = LyricsRequests.openConnection(lyricsUrl);
            if (connection.getResponseCode() != 200) {
                LyricsRequests.logFailure(name(), connection);
                return null;
            }
            final String ttml = Requester.parseString(connection);
            return TtmlParser.ttmlToLyrics(ttml, name(), null);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    @Nullable
    private String resolveLyricsUrl(TrackInfo track) throws IOException, JSONException {
        if (track.title().isEmpty() || track.artist().isEmpty()) {
            return null;
        }

        final StringBuilder url = new StringBuilder(BASE_URL);
        url.append("?track=").append(LyricsRequests.encode(track.title()));
        url.append("&artist=").append(LyricsRequests.encode(track.artist()));
        if (!track.album().isEmpty()) {
            url.append("&album=").append(LyricsRequests.encode(track.album()));
        }
        if (track.durationSeconds() > 0) {
            url.append("&duration=").append(track.durationSeconds());
        }

        HttpURLConnection connection = null;
        try {
            connection = LyricsRequests.openConnection(url.toString());
            final int responseCode = connection.getResponseCode();
            if (responseCode == 404) {
                connection.disconnect();
                return null;
            }
            if (responseCode != 200) {
                LyricsRequests.logFailure(name(), connection);
                return null;
            }
            final JSONObject response = Requester.parseJSONObject(connection);
            final JSONArray results = response.optJSONArray("results");
            if (results == null || results.length() == 0) {
                return null;
            }
            final JSONObject best = results.optJSONObject(0);
            if (best == null) {
                return null;
            }
            final String lyricsUrl = LyricsRequests.optString(best, "lyricsUrl");
            return lyricsUrl != null ? lyricsUrl : null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
}
