/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2269
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.requests;

import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.net.HttpURLConnection;

import app.morphe.extension.music.patches.lyrics.Lyrics;
import app.morphe.extension.music.patches.lyrics.TrackInfo;
import app.morphe.extension.shared.requests.Requester;

public final class BlyricsProvider implements LyricsProvider {

    private static final String BASE_URL = "https://lyrics-api.boidu.dev/getLyrics";

    @Override
    public String name() {
        return "bLyrics";
    }

    @Nullable
    @Override
    public Lyrics fetch(TrackInfo track) throws Exception {
        if (track.title().isEmpty() || track.artist().isEmpty()) {
            return null;
        }

        final StringBuilder url = new StringBuilder(BASE_URL);
        url.append("?s=").append(LyricsRequests.encode(track.title()));
        url.append("&a=").append(LyricsRequests.encode(track.artist()));
        if (track.durationSeconds() > 0) {
            url.append("&d=").append(track.durationSeconds());
        }
        if (!track.album().isEmpty()) {
            url.append("&al=").append(LyricsRequests.encode(track.album()));
        }

        HttpURLConnection connection = null;
        try {
            connection = LyricsRequests.openConnection(url.toString());
            if (connection.getResponseCode() != 200) {
                LyricsRequests.logFailure(name(), connection);
                return null;
            }
            final JSONObject root = Requester.parseJSONObject(connection);
            final String ttml = LyricsRequests.optString(root, "ttml");
            if (ttml == null) {
                return null;
            }
            return TtmlParser.ttmlToLyrics(ttml, name(), null);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
}
