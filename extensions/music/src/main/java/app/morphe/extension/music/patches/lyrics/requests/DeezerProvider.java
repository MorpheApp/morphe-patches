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

import app.morphe.extension.music.patches.lyrics.model.Lyrics;
import app.morphe.extension.music.patches.lyrics.model.LyricsLine;
import app.morphe.extension.music.patches.lyrics.model.TrackInfo;
import app.morphe.extension.music.patches.lyrics.model.TrackVersion;
import app.morphe.extension.music.patches.lyrics.parsers.LRCParser;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.requests.Requester;

public final class DeezerProvider extends ScoredSongProvider {

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
    protected List<ScoredSong> fetchScoredSongs(TrackInfo track) throws Exception {
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

        List<JSONObject> sorted = sortCandidates(searchResults, track);

        List<ScoredSong> scored = new ArrayList<>();
        for (JSONObject item : sorted) {
            if (scored.size() >= LyricsRequests.MAX_CANDIDATES) break;

            final long trackId = item.optLong("id", -1);
            if (trackId <= 0) continue;

            try {
                Lyrics lyrics = fetchLyricsByTrackId(trackId, arl);
                if (lyrics != null) {
                    String title = item.optString("title", "");
                    JSONObject artistObj = item.optJSONObject("artist");
                    String artist = artistObj != null ? artistObj.optString("name", "") : "";
                    long durationSec = item.optInt("duration", 0);
                    int score = LyricsRequests.scoreLyricsCandidate(
                            title, artist, durationSec, lyrics, track);
                    scored.add(new ScoredSong(score, lyrics, title, artist, durationSec));
                }
            } catch (Exception ex) {
                Logger.printDebug(() -> "Could not fetch Deezer lyrics by track ID", ex);
            }
        }

        scored.sort((a, b) -> Integer.compare(b.score(), a.score()));
        return scored;
    }

    private static int scoreCandidate(JSONObject item, TrackInfo track) {
        String title = item.optString("title", "");
        JSONObject artistObj = item.optJSONObject("artist");
        String artist = artistObj != null ? artistObj.optString("name", "") : "";
        JSONObject albumObj = item.optJSONObject("album");
        String album = albumObj != null ? albumObj.optString("title", "") : "";
        return LyricsRequests.scoreTrackCandidate(title, artist,
                item.optInt("duration", 0), album, track);
    }

    private static List<JSONObject> sortCandidates(JSONArray searchResults, TrackInfo track) {
        List<JSONObject> list = new ArrayList<>();
        for (int i = 0; i < searchResults.length(); i++) {
            JSONObject item = searchResults.optJSONObject(i);
            if (item != null) {
                list.add(item);
            }
        }
        list.sort((a, b) -> scoreCandidate(b, track) - scoreCandidate(a, track));
        List<JSONObject> passed = new ArrayList<>();
        for (JSONObject item : list) {
            if (scoreCandidate(item, track) >= LyricsRequests.SOFT_MIN) {
                passed.add(item);
            }
        }
        return passed.isEmpty() && !list.isEmpty() ? List.of(list.get(0)) : passed;
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
        } catch (Exception ex) {
            Logger.printDebug(() -> "Could not get Deezer JWT", ex);
            return null;
        }
    }

    private static synchronized void dropJwt() {
        cachedJwt = null;
        jwtExpiryMs = 0;
    }

    /** Lookups that succeeded, including tracks without a version. Failed requests are retried. */
    private static final Map<TrackInfo, TrackVersion> versions = Utils.createSizeRestrictedMap(64, true);

    /**
     * The version of the track, from the title_short and title_version fields of Deezer's own
     * search results. The variant is empty when Deezer has no exact match or the track has no
     * version.
     *
     * @param track Untouched metadata, see {@link TrackVersion#query}.
     * @throws IOException If a request fails.
     */
    public static TrackVersion fetchVersion(TrackInfo track) throws Exception {
        synchronized (versions) {
            TrackVersion cached = versions.get(track);
            if (cached != null) return cached;
        }
        TrackVersion result = lookupVersion(track);
        synchronized (versions) {
            versions.put(track, result);
        }
        return result;
    }

    private static TrackVersion lookupVersion(TrackInfo track) throws Exception {
        LyricsRequests.throttle(lastRequestTime, REQUEST_THROTTLE_MS);
        String url = SEARCH_URL
                + "?q=" + LyricsRequests.encode(track.artist() + " - " + track.title())
                + "&limit=10&output=json";
        JSONArray data = getJson(url).optJSONArray("data");
        if (data == null) throw new IOException("Deezer search has no results array");

        String wantedTitle = TrackVersion.normalize(track.title());
        String wantedArtist = TrackVersion.normalize(track.artist());
        // The full title is matched first. Short title equality alone would confuse the
        // original recording with a remix or acoustic version of that same song.
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0, length = data.length(); i < length; i++) {
                JSONObject item = data.optJSONObject(i);
                if (item == null) continue;
                JSONObject artist = item.optJSONObject("artist");
                if (artist == null || !wantedArtist.equals(TrackVersion.normalize(artist.optString("name", "")))) {
                    continue;
                }
                String full = item.optString("title", "");
                String shortTitle = item.optString("title_short", "");
                String version = item.optString("title_version", "");
                final boolean matches = pass == 0
                        ? wantedTitle.equals(TrackVersion.normalize(full))
                        : TrackVersion.matchesStructuredTitle(track.title(), full, shortTitle, version);
                if (matches && !shortTitle.isBlank()) {
                    return new TrackVersion(shortTitle, TrackVersion.clean(version));
                }
            }
        }
        return new TrackVersion(track.title(), "");
    }

    /** A Deezer error comes back as a 200 with an error object, so that counts as a failure too. */
    private static JSONObject getJson(String url) throws Exception {
        HttpURLConnection connection = LyricsRequests.openConnection(url, Map.of("Accept", "application/json"));
        try {
            final int httpCode = connection.getResponseCode();
            if (httpCode != Requester.HTTP_STATUS_CODE_SUCCESS) throw new IOException("Deezer HTTP " + httpCode);
            JSONObject json = Requester.parseJSONObject(connection);
            if (json.has("error")) throw new IOException("Deezer error: " + json.optString("error"));
            return json;
        } finally {
            connection.disconnect();
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
            connection = LyricsRequests.openConnection(url, Map.of("Accept", "application/json"));
        } catch (IOException ex) {
            Logger.printDebug(() -> "Could not open Deezer search connection", ex);
            return null;
        }

        try {
            final int httpCode = connection.getResponseCode();
            if (httpCode != Requester.HTTP_STATUS_CODE_SUCCESS) return null;
            JSONObject response = Requester.parseJSONObject(connection);
            return response.optJSONArray("data");
        } catch (IOException ex) {
            Logger.printDebug(() -> "Could not parse Deezer search response", ex);
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

        JSONArray syncedLines = lyrics.optJSONArray("synchronizedLines");
        if (syncedLines != null && syncedLines.length() > 0) {
            String rawFormat = syncedLines.toString();
            Lyrics synced = parseSyncedLyrics(syncedLines, trackId, rawFormat, creditLines(lyrics));
            if (synced != null) {
                return synced;
            }
        }

        String text = LyricsRequests.optString(lyrics, "text");
        if (text != null) {
            return parsePlainText(text, trackId, text, creditLines(lyrics));
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
        } catch (IOException ex) {
            Logger.printDebug(() -> "Could not postPipe", ex);
            return null;
        } finally {
            connection.disconnect();
        }
    }

    @Nullable
    private static List<String> creditLines(JSONObject lyrics) {
        List<String> credits = new ArrayList<>(2);
        String writers = flatten(lyrics.opt("writers"));
        if (writers != null) {
            credits.add("Writer(s): " + writers);
        }
        String copyright = LyricsRequests.optString(lyrics, "copyright");
        if (copyright != null) {
            credits.add("Copyright: " + copyright);
        }
        return credits.isEmpty() ? null : credits;
    }

    /** The writers field is a string on some tracks and a list on others. */
    @Nullable
    private static String flatten(@Nullable Object value) {
        if (value instanceof String string) {
            return string.trim().isEmpty() ? null : string.trim();
        }
        if (value instanceof JSONArray array) {
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < array.length(); i++) {
                String entry = array.optString(i, "").trim();
                if (entry.isEmpty()) continue;
                //noinspection SizeReplaceableByIsEmpty
                if (builder.length() > 0) builder.append(", ");
                builder.append(entry);
            }
            //noinspection SizeReplaceableByIsEmpty
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

        LRCParser.fillEstimatedEnds(lines);

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
                rawFormat, "txt", sourceUrl);
    }

    public static boolean validateArl(String arl) {
        if (arl == null || arl.trim().isEmpty() || "null".equals(arl)) return false;
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
        } catch (Exception ex) {
            Logger.printDebug(() -> "Could not get Deezer account ID", ex);
            return 0;
        }
    }
}
