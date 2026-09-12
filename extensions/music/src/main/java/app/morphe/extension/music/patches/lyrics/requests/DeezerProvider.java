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
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import app.morphe.extension.music.patches.lyrics.Lyrics;
import app.morphe.extension.music.patches.lyrics.LyricsLine;
import app.morphe.extension.music.patches.lyrics.TrackInfo;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.requests.Requester;

public final class DeezerProvider implements LyricsProvider {

    private static final String SEARCH_URL = "https://api.deezer.com/search";
    private static final String GW_URL = "https://www.deezer.com/ajax/gw-light.php";


    private static final long REQUEST_THROTTLE_MS = 250;
    private static final AtomicLong lastRequestTime = new AtomicLong(0);

    private static String cachedArl;
    private static String cachedApiToken;
    private static String cachedSid;

    private static class Session {
        final String apiToken;
        final String sid;
        Session(String apiToken, String sid) {
            this.apiToken = apiToken;
            this.sid = sid;
        }
    }

    @Override
    public String name() {
        return "Deezer";
    }

    @Override
    public boolean hasCandidates() {
        return true;
    }

    @Nullable
    @Override
    public Lyrics fetch(TrackInfo track) throws Exception {
        final List<Lyrics> candidates = fetchCandidates(track);
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    @Override
    public List<Lyrics> fetchCandidates(TrackInfo track) throws Exception {
        final String arl = getArl();
        if (arl == null || arl.isEmpty()) {
            return java.util.Collections.emptyList();
        }

        final Session session = getSession(arl);
        if (session == null) {
            return java.util.Collections.emptyList();
        }

        final JSONArray searchResults = searchTracks(track);
        if (searchResults == null || searchResults.length() == 0) {
            return java.util.Collections.emptyList();
        }

        final List<Lyrics> results = new ArrayList<>();
        for (int i = 0; i < searchResults.length() && results.size() < 5; i++) {
            final JSONObject item = searchResults.optJSONObject(i);
            if (item == null) continue;

            final long trackId = item.optLong("id", -1);
            if (trackId <= 0) continue;

            try {
                final Lyrics lyrics = fetchLyricsByTrackId(trackId, arl, session);
                if (lyrics != null) {
                    results.add(lyrics);
                }
            } catch (Exception ignored) {
            }
        }
        return results;
    }

    @Nullable
    private static String getArl() {
        final String arl = Settings.DEEZER_ARL.get();
        if (arl == null || arl.isEmpty() || "null".equals(arl)) {
            return null;
        }
        return arl;
    }

    @Nullable
    private static synchronized Session getSession(String arl) {
        if (arl.equals(cachedArl) && cachedApiToken != null) {
            return new Session(cachedApiToken, cachedSid);
        }
        try {
            final String url = GW_URL
                    + "?method=deezer.getUserData"
                    + "&input=3"
                    + "&api_version=1.0"
                    + "&api_token=";

            final HttpURLConnection connection = postConnection(url, "{}", arl, null);
            if (connection == null) return null;

            String sid = null;
            for (java.util.Map.Entry<String, java.util.List<String>> entry : connection.getHeaderFields().entrySet()) {
                if ("Set-Cookie".equalsIgnoreCase(entry.getKey())) {
                    for (String cookie : entry.getValue()) {
                        if (cookie.startsWith("sid=")) {
                            sid = cookie.split(";")[0].substring(4);
                            break;
                        }
                    }
                }
            }

            final int code = connection.getResponseCode();
            if (code != 200) {
                connection.disconnect();
                return null;
            }

            final JSONObject response = Requester.parseJSONObject(connection);
            connection.disconnect();

            final JSONObject results = response.optJSONObject("results");
            if (results == null) return null;

            final String apiToken = results.optString("checkForm", null);
            if (apiToken == null || apiToken.isEmpty()) return null;

            cachedArl = arl;
            cachedApiToken = apiToken;
            cachedSid = sid;
            return new Session(apiToken, sid);
        } catch (Exception ignored) {
            return null;
        }
    }

    @Nullable
    private JSONArray searchTracks(TrackInfo track) throws Exception {
        LyricsRequests.throttle(lastRequestTime, REQUEST_THROTTLE_MS);

        final String query = track.artist() + " - " + track.title();
        final String url = SEARCH_URL
                + "?q=" + LyricsRequests.encode(query)
                + "&limit=10"
                + "&output=json";

        final HttpURLConnection connection = openConnection(url);
        if (connection == null) return null;

        try {
            final int httpCode = connection.getResponseCode();
            if (httpCode != 200) return null;
            final JSONObject response = Requester.parseJSONObject(connection);
            return response.optJSONArray("data");
        } catch (IOException ignored) {
            return null;
        } finally {
            connection.disconnect();
        }
    }

    @Nullable
    private Lyrics fetchLyricsByTrackId(long trackId, String arl, Session session) throws Exception {
        LyricsRequests.throttle(lastRequestTime, REQUEST_THROTTLE_MS);

        final String url = GW_URL
                + "?method=song.getLyrics"
                + "&input=3"
                + "&api_version=1.0"
                + "&api_token=" + LyricsRequests.encode(session.apiToken);

        final String body = "{\"sng_id\":" + trackId + "}";

        final HttpURLConnection connection = postConnection(url, body, arl, session.sid);
        if (connection == null) return null;

        try {
            final int httpCode = connection.getResponseCode();
            if (httpCode != 200) return null;

            final JSONObject response = Requester.parseJSONObject(connection);
            final JSONObject error = response.optJSONObject("error");
            if (error != null && error.length() > 0) return null;

            final JSONObject results = response.optJSONObject("results");
            if (results == null) return null;

            final String lyricsText = results.optString("LYRICS_TEXT", null);
            final JSONArray syncJson = results.optJSONArray("LYRICS_SYNC_JSON");
            final String rawFormat = results.toString();

            if (syncJson != null && syncJson.length() > 0) {
                return parseSyncedLyrics(syncJson, trackId, rawFormat);
            } else if (lyricsText != null && !lyricsText.isEmpty()) {
                return parsePlainText(lyricsText, trackId, rawFormat);
            }

            return null;
        } catch (IOException ignored) {
            return null;
        } finally {
            connection.disconnect();
        }
    }

    @Nullable
    private Lyrics parseSyncedLyrics(JSONArray syncJson, long trackId, String rawFormat)
            throws JSONException {
        final List<LyricsLine> lines = new ArrayList<>();

        for (int i = 0; i < syncJson.length(); i++) {
            final JSONObject item = syncJson.optJSONObject(i);
            if (item == null) continue;

            final long startMs = item.optLong("milliseconds", 0);
            final String text = item.optString("line", "");
            if (text.isEmpty()) continue;

            lines.add(new LyricsLine(startMs, text));
        }

        if (lines.isEmpty()) return null;

        final String sourceUrl = "https://www.deezer.com/track/" + trackId;
        return new Lyrics(lines, name(), true, null, null, null, null,
                rawFormat, "dzr.json", sourceUrl);
    }

    @Nullable
    private Lyrics parsePlainText(String text, long trackId, String rawFormat) {
        final List<LyricsLine> lines = LyricsRequests.parsePlainTextLines(text);
        if (lines.isEmpty()) return null;

        final String sourceUrl = "https://www.deezer.com/track/" + trackId;
        return new Lyrics(lines, name(), false, null, null, null, null,
                rawFormat, "dzr.json", sourceUrl);
    }

    @Nullable
    private static HttpURLConnection openConnection(String url) {
        try {
            final HttpURLConnection connection = (HttpURLConnection) new java.net.URL(url).openConnection();
            connection.setRequestMethod("GET");
            connection.setRequestProperty("User-Agent", LyricsRequests.userAgent());
            connection.setRequestProperty("Accept", "application/json");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(15000);
            return connection;
        } catch (Exception ignored) {
            return null;
        }
    }

    @Nullable
    private static HttpURLConnection postConnection(String url, String body, String arl, @Nullable String sid) {
        try {
            final HttpURLConnection connection = (HttpURLConnection) new java.net.URL(url).openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("User-Agent", LyricsRequests.userAgent());
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            String cookie = "arl=" + arl;
            if (sid != null && !sid.isEmpty()) {
                cookie += "; sid=" + sid;
            }
            connection.setRequestProperty("Cookie", cookie);
            connection.setRequestProperty("Accept", "application/json");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(15000);
            connection.setDoOutput(true);
            final byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            try (java.io.OutputStream output = connection.getOutputStream()) {
                output.write(bytes);
            }
            return connection;
        } catch (Exception ignored) {
            return null;
        }
    }

    public static boolean validateArl(String arl) {
        if (arl == null || arl.isBlank() || "null".equals(arl)) return false;
        try {
            final HttpURLConnection connection = (HttpURLConnection)
                    new URL("https://api.deezer.com/user/me").openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(8000);
            connection.setRequestProperty("User-Agent", LyricsRequests.userAgent());
            connection.setRequestProperty("Cookie", "arl=" + arl);
            final int code = connection.getResponseCode();
            connection.disconnect();
            return code == 200;
        } catch (Exception ignored) {
            return false;
        }
    }
}
