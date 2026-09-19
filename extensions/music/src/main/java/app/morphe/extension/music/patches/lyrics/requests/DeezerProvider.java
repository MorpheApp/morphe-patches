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
    /** The arl cookie has to be sent to this host, not to www.deezer.com. */
    private static final String AUTH_URL = "https://auth.deezer.com/login/arl?jo=p&rto=c&i=c";
    private static final String PIPE_URL = "https://pipe.deezer.com/api";

    /** Tokens live about six minutes, so they are replaced well before that. */
    private static final long JWT_TTL_MS = 4 * 60 * 1000;

    private static final String LYRICS_QUERY =
            "query SynchronizedTrackLyrics($trackId: String!) {"
                    + " track(trackId: $trackId) { id lyrics { id text copyright writers"
                    + " synchronizedLines { lrcTimestamp line milliseconds duration } } } }";

    /** Same query without the credit fields, in case the schema drops them. */
    private static final String LYRICS_QUERY_MINIMAL =
            "query SynchronizedTrackLyrics($trackId: String!) {"
                    + " track(trackId: $trackId) { id lyrics { id text"
                    + " synchronizedLines { lrcTimestamp line milliseconds duration } } } }";

    private static final long REQUEST_THROTTLE_MS = 250;
    private static final AtomicLong lastRequestTime = new AtomicLong(0);

    private static String cachedArl;
    private static String cachedJwt;
    private static long jwtExpiryMs;

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
        if (arl == null) {
            return Collections.emptyList();
        }

        String jwt = getJwt(arl);
        if (jwt == null) {
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
                Lyrics lyrics = fetchLyricsByTrackId(trackId, arl);
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
        if (arl.isEmpty() || "null".equals(arl)) {
            return null;
        }
        return arl;
    }

    /**
     * Trades the arl cookie for the short-lived bearer token that the GraphQL API wants.
     */
    @Nullable
    private static synchronized String getJwt(String arl) {
        final long now = System.currentTimeMillis();
        if (arl.equals(cachedArl) && cachedJwt != null && now < jwtExpiryMs) {
            return cachedJwt;
        }
        try {
            Map<String, String> headers = new HashMap<>();
            headers.put("Cookie", "arl=" + arl);
            headers.put("Accept", "*/*");

            HttpURLConnection connection = LyricsRequests.postJson(AUTH_URL, "", headers);
            try {
                if (connection.getResponseCode() != Requester.HTTP_STATUS_CODE_SUCCESS) {
                    LyricsRequests.logFailure("Deezer", connection);
                    return null;
                }
                // The answer is sent as text/plain even though it is JSON.
                JSONObject response = new JSONObject(Requester.parseString(connection));
                String jwt = LyricsRequests.optString(response, "jwt");
                if (jwt == null) {
                    return null;
                }
                cachedArl = arl;
                cachedJwt = jwt;
                jwtExpiryMs = now + JWT_TTL_MS;
                return jwt;
            } finally {
                connection.disconnect();
            }
        } catch (Exception ignored) {
            return null;
        }
    }

    private static synchronized void dropJwt() {
        cachedJwt = null;
        jwtExpiryMs = 0;
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

        try {
            final int httpCode = connection.getResponseCode();
            if (httpCode != Requester.HTTP_STATUS_CODE_SUCCESS) return null;
            JSONObject response = Requester.parseJSONObject(connection);
            return response.optJSONArray("data");
        } catch (IOException ignored) {
            return null;
        } finally {
            connection.disconnect();
        }
    }

    @Nullable
    private Lyrics fetchLyricsByTrackId(long trackId, String arl) throws Exception {
        JSONObject response = postPipe(payload(trackId, LYRICS_QUERY), arl, true);
        if (response != null && response.has("errors")) {
            // The credit fields are not in every schema version; ask for the lyrics alone.
            response = postPipe(payload(trackId, LYRICS_QUERY_MINIMAL), arl, true);
        }
        JSONObject lyrics = response == null
                ? null : LyricsRequests.optPath(response, "data", "track", "lyrics");
        if (lyrics == null) {
            return null;
        }

        final String rawFormat = lyrics.toString();
        JSONArray syncedLines = lyrics.optJSONArray("synchronizedLines");
        if (syncedLines != null && syncedLines.length() > 0) {
            Lyrics synced = parseSyncedLyrics(syncedLines, trackId, rawFormat, creditLines(lyrics));
            if (synced != null) {
                return synced;
            }
        }

        final String text = LyricsRequests.optString(lyrics, "text");
        if (text != null) {
            return parsePlainText(text, trackId, rawFormat, creditLines(lyrics));
        }
        return null;
    }

    private static String payload(long trackId, String query) throws Exception {
        JSONObject variables = new JSONObject();
        variables.put("trackId", String.valueOf(trackId));
        JSONObject payload = new JSONObject();
        payload.put("operationName", "SynchronizedTrackLyrics");
        payload.put("variables", variables);
        payload.put("query", query);
        return payload.toString();
    }

    /**
     * @param retryOnExpiry Fetches a fresh token and repeats the call once when the token
     *                      is refused, because tokens expire after a few minutes.
     */
    @Nullable
    private static JSONObject postPipe(String body, String arl, boolean retryOnExpiry)
            throws Exception {
        LyricsRequests.throttle(lastRequestTime, REQUEST_THROTTLE_MS);

        String jwt = getJwt(arl);
        if (jwt == null) {
            return null;
        }

        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer " + jwt);
        headers.put("Accept", "application/json");

        HttpURLConnection connection = LyricsRequests.postJson(PIPE_URL, body, headers);
        try {
            final int httpCode = connection.getResponseCode();
            if (httpCode == 401 || httpCode == 403) {
                dropJwt();
                return retryOnExpiry ? postPipe(body, arl, false) : null;
            }
            if (httpCode != Requester.HTTP_STATUS_CODE_SUCCESS) {
                LyricsRequests.logFailure("Deezer", connection);
                return null;
            }
            return Requester.parseJSONObject(connection);
        } catch (IOException ignored) {
            return null;
        } finally {
            connection.disconnect();
        }
    }

    @Nullable
    private static List<String> creditLines(JSONObject lyrics) {
        List<String> credits = new ArrayList<>(2);
        final String writers = flatten(lyrics.opt("writers"));
        if (writers != null) {
            credits.add("Written by " + writers);
        }
        final String copyright = LyricsRequests.optString(lyrics, "copyright");
        if (copyright != null) {
            credits.add(copyright);
        }
        return credits.isEmpty() ? null : credits;
    }

    /** The writers field is a string on some tracks and a list on others. */
    @Nullable
    private static String flatten(@Nullable Object value) {
        if (value instanceof String string) {
            return string.isBlank() ? null : string.trim();
        }
        if (value instanceof JSONArray array) {
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < array.length(); i++) {
                final String entry = array.optString(i, "").trim();
                if (entry.isEmpty()) continue;
                if (builder.length() > 0) builder.append(", ");
                builder.append(entry);
            }
            return builder.length() == 0 ? null : builder.toString();
        }
        return null;
    }

    @Nullable
    private Lyrics parseSyncedLyrics(JSONArray syncedLines, long trackId, String rawFormat,
                                     @Nullable List<String> creditLines) {
        List<LyricsLine> lines = new ArrayList<>();

        for (int i = 0; i < syncedLines.length(); i++) {
            JSONObject item = syncedLines.optJSONObject(i);
            if (item == null) continue;

            final long startMs = item.optLong("milliseconds", 0);
            String text = item.optString("line", "");
            if (text.isEmpty()) continue;

            lines.add(new LyricsLine(startMs, text));
        }

        if (lines.isEmpty()) return null;

        String sourceUrl = "https://www.deezer.com/track/" + trackId;
        return new Lyrics(lines, name(), true, null, null, null, creditLines,
                rawFormat, "dzr.json", sourceUrl);
    }

    @Nullable
    private Lyrics parsePlainText(String text, long trackId, String rawFormat,
                                  @Nullable List<String> creditLines) {
        List<LyricsLine> lines = LyricsRequests.parsePlainTextLines(text);
        if (lines.isEmpty()) return null;

        String sourceUrl = "https://www.deezer.com/track/" + trackId;
        return new Lyrics(lines, name(), false, null, null, null, creditLines,
                rawFormat, "dzr.json", sourceUrl);
    }

    public static boolean validateArl(String arl) {
        if (arl == null || arl.isBlank() || "null".equals(arl)) return false;
        return accountId(arl) > 0;
    }

    /**
     * @return the account behind the arl, or 0 when the cookie is rejected. Deezer answers
     *         an anonymous session instead of an error, so the user id is what tells them apart.
     */
    private static long accountId(String arl) {
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
            try {
                if (connection.getResponseCode() != Requester.HTTP_STATUS_CODE_SUCCESS) {
                    return 0;
                }
                JSONObject response = Requester.parseJSONObject(connection);
                JSONObject user = LyricsRequests.optPath(response, "results", "USER");
                return user == null ? 0 : user.optLong("USER_ID", 0);
            } finally {
                connection.disconnect();
            }
        } catch (Exception ignored) {
            return 0;
        }
    }
}
