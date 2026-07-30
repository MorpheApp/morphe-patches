/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2269
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.requests;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.util.List;

import app.morphe.extension.music.patches.lyrics.LrcParser;
import app.morphe.extension.music.patches.lyrics.Lyrics;
import app.morphe.extension.music.patches.lyrics.LyricsLine;
import app.morphe.extension.music.patches.lyrics.TrackInfo;
import app.morphe.extension.shared.Logger;

/**
 * LRCLIB, the open lyrics database used by Metrolist, InnerTune and ViMusic.
 *
 * @see <a href="https://lrclib.net/docs">API documentation</a>
 */
public final class LrcLibProvider implements LyricsProvider {

    private static final String BASE_URL = "https://lrclib.net/api/";

    @NonNull
    @Override
    public String name() {
        return "LRCLIB";
    }

    @Nullable
    @Override
    public Lyrics fetch(@NonNull TrackInfo track) throws Exception {
        // The exact endpoint matches on duration as well, which gives the best timings,
        // but it fails for any track whose duration differs from the database entry.
        Lyrics exact = fetchExact(track);
        if (exact != null) {
            return exact;
        }
        return fetchSearch(track);
    }

    @Nullable
    private Lyrics fetchExact(@NonNull TrackInfo track) throws Exception {
        StringBuilder url = new StringBuilder(BASE_URL);
        url.append("get?track_name=").append(encode(track.title()));
        url.append("&artist_name=").append(encode(track.artist()));
        if (!track.album().isEmpty()) {
            url.append("&album_name=").append(encode(track.album()));
        }
        if (track.durationSeconds() > 0) {
            url.append("&duration=").append(track.durationSeconds());
        }

        JSONObject response = getJsonObject(url.toString());
        if (response == null) {
            return null;
        }
        return toLyrics(response);
    }

    @Nullable
    private Lyrics fetchSearch(@NonNull TrackInfo track) throws Exception {
        String url = BASE_URL + "search?track_name=" + encode(track.title())
                + "&artist_name=" + encode(track.artist());

        HttpURLConnection connection = LyricsRequests.openConnection(url);
        if (connection.getResponseCode() != 200) {
            LyricsRequests.logFailure(name(), connection);
            return null;
        }

        JSONArray results = LyricsRequests.parseJsonArrayAndDisconnect(connection);
        if (results.length() == 0) {
            return null;
        }

        // Prefer the candidate closest in duration, since same titled tracks are common.
        JSONObject best = null;
        int bestDelta = Integer.MAX_VALUE;
        for (int i = 0; i < results.length(); i++) {
            JSONObject candidate = results.optJSONObject(i);
            if (candidate == null) {
                continue;
            }
            if (track.durationSeconds() <= 0) {
                best = candidate;
                break;
            }
            int delta = Math.abs(candidate.optInt("duration", 0) - track.durationSeconds());
            if (delta < bestDelta) {
                bestDelta = delta;
                best = candidate;
            }
        }

        if (best == null) {
            return null;
        }
        return toLyrics(best);
    }

    @Nullable
    private Lyrics toLyrics(@NonNull JSONObject response) {
        if (response.optBoolean("instrumental", false)) {
            Logger.printDebug(() -> "LRCLIB reports an instrumental track");
            return Lyrics.NOT_FOUND;
        }

        String synced = optString(response, "syncedLyrics");
        if (synced != null) {
            List<LyricsLine> lines = LrcParser.parseSynced(synced);
            if (!lines.isEmpty()) {
                return new Lyrics(lines, name(), true);
            }
        }

        String plain = optString(response, "plainLyrics");
        if (plain != null) {
            List<LyricsLine> lines = LrcParser.parsePlain(plain);
            if (!lines.isEmpty()) {
                return new Lyrics(lines, name(), false);
            }
        }

        return null;
    }

    @Nullable
    private JSONObject getJsonObject(@NonNull String url) throws IOException, JSONException {
        HttpURLConnection connection = LyricsRequests.openConnection(url);
        final int responseCode = connection.getResponseCode();
        if (responseCode == 404) {
            connection.disconnect();
            return null;
        }
        if (responseCode != 200) {
            LyricsRequests.logFailure(name(), connection);
            return null;
        }
        return LyricsRequests.parseJsonObjectAndDisconnect(connection);
    }

    @Nullable
    private static String optString(@NonNull JSONObject object, @NonNull String key) {
        if (object.isNull(key)) {
            return null;
        }
        String value = object.optString(key, "");
        return value.isBlank() ? null : value;
    }

    /**
     * The Charset overload of encode() needs API 33, so the charset is named instead.
     */
    @NonNull
    @SuppressWarnings("CharsetObjectCanBeUsed")
    private static String encode(@NonNull String value) throws UnsupportedEncodingException {
        return URLEncoder.encode(value, "UTF-8");
    }
}
