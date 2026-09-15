/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2625
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
        List<Lyrics> candidates = fetchCandidates(track);
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    @Override
    public List<Lyrics> fetchCandidates(TrackInfo track) throws Exception {
        String arl = getArl();
        if (arl == null || arl.isEmpty()) {
            return Collections.emptyList();
        }

        Session session = getSession(arl);
        if (session == null) {
            return Collections.emptyList();
        }

        JSONArray searchResults = searchTracks(track);
        if (searchResults == null || searchResults.length() == 0) {
            return Collections.emptyList();
        }

        List<Lyrics> results = new ArrayList<>();
        for (int i = 0; i < searchResults.length() && results.size() < LyricsRequests.MAX_CANDIDATES; i++) {
            JSONObject item = searchResults.optJSONObject(i);
            if (item == null) continue;

            final long trackId = item.optLong("id", -1);
            if (trackId <= 0) continue;

            try {
                Lyrics lyrics = fetchLyricsByTrackId(trackId, arl, session);
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
        String arl = Settings.DEEZER_ARL.get();
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
            String url = GW_URL
                    + "?method=deezer.getUserData"
                    + "&input=3"
                    + "&api_version=1.0"
                    + "&api_token=";

            Map<String, String> headers = new HashMap<>();
            headers.put("Cookie", "arl=" + arl);
            headers.put("Accept", "application/json");

            HttpURLConnection connection = LyricsRequests.postJson(url, "{}", headers);
            if (connection == null) return null;

            String sid = null;
            for (Map.Entry<String, List<String>> entry : connection.getHeaderFields().entrySet()) {
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

            JSONObject response = Requester.parseJSONObject(connection);
            connection.disconnect();

            JSONObject results = response.optJSONObject("results");
            if (results == null) return null;

            String apiToken = results.optString("checkForm", null);
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

        String query = track.artist() + " - " + track.title();
        String url = SEARCH_URL
                + "?q=" + LyricsRequests.encode(query)
                + "&limit=10"
                + "&output=json";

        HttpURLConnection connection;
        try {
            connection = LyricsRequests.openConnection(url, 10000, 15000,
                    Map.of("Accept", "application/json"));
        } catch (IOException ignored) {
            return null;
        }
        if (connection == null) return null;

        try {
            final int httpCode = connection.getResponseCode();
            if (httpCode != 200) return null;
            JSONObject response = Requester.parseJSONObject(connection);
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

        String url = GW_URL
                + "?method=song.getLyrics"
                + "&input=3"
                + "&api_version=1.0"
                + "&api_token=" + LyricsRequests.encode(session.apiToken);

        String body = "{\"sng_id\":" + trackId + "}";

        String cookie = "arl=" + arl;
        if (session.sid != null && !session.sid.isEmpty()) {
            cookie += "; sid=" + session.sid;
        }
        Map<String, String> headers = new HashMap<>();
        headers.put("Cookie", cookie);
        headers.put("Accept", "application/json");

        HttpURLConnection connection;
        try {
            connection = LyricsRequests.postJson(url, body, headers);
        } catch (IOException ignored) {
            return null;
        }
        if (connection == null) return null;

        try {
            final int httpCode = connection.getResponseCode();
            if (httpCode != 200) return null;

            JSONObject response = Requester.parseJSONObject(connection);
            JSONObject error = response.optJSONObject("error");
            if (error != null && error.length() > 0) return null;

            JSONObject results = response.optJSONObject("results");
            if (results == null) return null;

            String lyricsText = results.optString("LYRICS_TEXT", null);
            JSONArray syncJson = results.optJSONArray("LYRICS_SYNC_JSON");
            String rawFormat = results.toString();

            if (syncJson != null && syncJson.length() > 0) {
                return parseSyncedLyrics(syncJson, trackId, rawFormat);
            } else if (!lyricsText.isEmpty()) {
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
        List<LyricsLine> lines = new ArrayList<>();

        for (int i = 0; i < syncJson.length(); i++) {
            JSONObject item = syncJson.optJSONObject(i);
            if (item == null) continue;

            final long startMs = item.optLong("milliseconds", 0);
            String text = item.optString("line", "");
            if (text.isEmpty()) continue;

            lines.add(new LyricsLine(startMs, text));
        }

        if (lines.isEmpty()) return null;

        String sourceUrl = "https://www.deezer.com/track/" + trackId;
        return new Lyrics(lines, name(), true, null, null, null, null,
                rawFormat, "dzr.json", sourceUrl);
    }

    @Nullable
    private Lyrics parsePlainText(String text, long trackId, String rawFormat) {
        List<LyricsLine> lines = LyricsRequests.parsePlainTextLines(text);
        if (lines.isEmpty()) return null;

        String sourceUrl = "https://www.deezer.com/track/" + trackId;
        return new Lyrics(lines, name(), false, null, null, null, null,
                rawFormat, "dzr.json", sourceUrl);
    }

    public static boolean validateArl(String arl) {
        if (arl == null || arl.isBlank() || "null".equals(arl)) return false;
        try {
            HttpURLConnection connection = LyricsRequests.openConnection(
                    "https://api.deezer.com/user/me", 5000, 8000,
                    Map.of("Cookie", "arl=" + arl));
            final int code = connection.getResponseCode();
            connection.disconnect();
            return code == 200;
        } catch (Exception ignored) {
            return false;
        }
    }
}
