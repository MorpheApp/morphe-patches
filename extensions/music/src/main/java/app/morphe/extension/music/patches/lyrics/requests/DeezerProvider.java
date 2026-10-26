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
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import app.morphe.extension.music.patches.lyrics.model.Lyrics;
import app.morphe.extension.music.patches.lyrics.model.PairedPunctuation;
import app.morphe.extension.music.patches.lyrics.model.LyricsLine;
import app.morphe.extension.music.patches.lyrics.model.TrackInfo;
import app.morphe.extension.music.patches.lyrics.parsers.LRCParser;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
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

    /**
     * The version of the matched track, such as "Acoustic", from Deezer's own title_version field.
     * Empty when Deezer has no exact match or the track has no version. Throws when a request
     * fails, so the caller can report it.
     */
    public record TrackVersion(String title, String variant) {}

    /** Variant matching needs untouched metadata, rather than the transliterated lyric query. */
    public static TrackInfo versionQuery(TrackInfo track, @Nullable String rawTitle, @Nullable String rawArtist) {
        return new TrackInfo(rawTitle == null || rawTitle.isBlank() ? track.title() : rawTitle,
                rawArtist == null || rawArtist.isBlank() ? track.artist() : rawArtist,
                track.album(), track.durationSeconds());
    }

    // Successful results, including tracks without a version, survive track changes. Failed
    // requests are not cached, so a temporary network error can be retried on the next play.
    private static final Map<TrackInfo, TrackVersion> versions = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<TrackInfo, TrackVersion> eldest) {
            return size() > 64;
        }
    };

    public static synchronized TrackVersion fetchVersion(TrackInfo track) throws Exception {
        TrackVersion cached = versions.get(track);
        if (cached != null) return cached;
        TrackVersion result = lookupVersion(track);
        versions.put(track, result);
        return result;
    }

    private static TrackVersion lookupVersion(TrackInfo track) throws Exception {
        LyricsRequests.throttle(lastRequestTime, REQUEST_THROTTLE_MS);
        String url = SEARCH_URL
                + "?q=" + LyricsRequests.encode(track.artist() + " - " + track.title())
                + "&limit=10&output=json";
        JSONArray data = getJson(url).optJSONArray("data");
        if (data == null) throw new IOException("Deezer search has no results array");

        final String wantedTitle = normalize(track.title());
        final String wantedArtist = normalize(track.artist());
        // Prefer the full backend title. Short-title equality alone would confuse the original
        // recording with a remix or acoustic version of that same song.
        for (int pass = 0; pass < 2; pass++) for (int i = 0; i < data.length(); i++) {
            JSONObject item = data.optJSONObject(i);
            if (item == null) continue;
            JSONObject artist = item.optJSONObject("artist");
            if (artist == null || !wantedArtist.equals(normalize(artist.optString("name", "")))) continue;
            final String full = item.optString("title", "");
            final String shortTitle = item.optString("title_short", "");
            if (pass == 0 ? !wantedTitle.equals(normalize(full))
                    : !matchesStructuredTitle(track.title(), full, shortTitle, item.optString("title_version", ""))) continue;
            final long id = item.optLong("id", 0);
            if (id <= 0) continue;
            LyricsRequests.throttle(lastRequestTime, REQUEST_THROTTLE_MS);
            final JSONObject detail = getJson("https://api.deezer.com/track/" + id);
            final String title = detail.optString("title_short", "");
            if (title.isBlank()) throw new IOException("Deezer track has no short title");
            return new TrackVersion(title, cleanVersion(detail.optString("title_version", "")));
        }
        return new TrackVersion(track.title(), "");
    }

    /**
     * Strips punctuation from both ends of the version, but only punctuation with no partner left
     * in the rest of the version. "(Acoustic)" becomes "Acoustic", "Acoustic (Deluxe)" keeps its
     * brackets, and "Acoustic (Deluxe)," loses only the comma.
     */
    static String cleanVersion(String version) {
        String s = version.strip();
        boolean changed = true;
        while (changed && !s.isEmpty()) {
            changed = false;
            final String core = core(s);
            final int first = s.codePointAt(0);
            if (edgePunctuation(first) && !hasPartner(first, core)) {
                s = s.substring(Character.charCount(first)).strip();
                changed = true;
                continue;
            }
            final int last = s.codePointBefore(s.length());
            if (edgePunctuation(last) && !hasPartner(last, core)) {
                s = s.substring(0, s.length() - Character.charCount(last)).strip();
                changed = true;
            }
        }
        return s;
    }

    /** The version without the punctuation at either end. */
    private static String core(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && (edgePunctuation(s.codePointAt(start)) || Character.isWhitespace(s.codePointAt(start))))
            start += Character.charCount(s.codePointAt(start));
        while (end > start && (edgePunctuation(s.codePointBefore(end)) || Character.isWhitespace(s.codePointBefore(end))))
            end -= Character.charCount(s.codePointBefore(end));
        return s.substring(start, end);
    }

    private static boolean edgePunctuation(int cp) {
        return switch (Character.getType(cp)) {
            case Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION,
                    Character.START_PUNCTUATION, Character.END_PUNCTUATION,
                    Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
                    Character.OTHER_PUNCTUATION -> true;
            default -> false;
        };
    }

    private static boolean hasPartner(int cp, String core) {
        final String symbol = new String(Character.toChars(cp));
        for (PairedPunctuation.Pair pair : PairedPunctuation.PAIRS) {
            if (pair.opening().equals(symbol) && core.contains(pair.closing())
                    || pair.closing().equals(symbol) && core.contains(pair.opening())) return true;
        }
        return false;
    }

    /** Compare complete titles composed from structured fields, never parse the user's title. */
    static boolean matchesStructuredTitle(String displayed, String full, String shortTitle, String version) {
        if (shortTitle.isBlank()) return false;
        final String wanted = normalize(displayed);
        final String variant = cleanVersion(version);
        if (variant.isEmpty()) {
            return wanted.equals(normalize(shortTitle)) && normalize(full).equals(normalize(shortTitle));
        }
        return wanted.equals(normalize(shortTitle + " (" + variant + ")"));
    }

    static String normalize(String text) {
        return text.trim().toLowerCase(Locale.ROOT);
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
        final String writers = flatten(lyrics.opt("writers"));
        if (writers != null) {
            credits.add("Writer(s): " + writers);
        }
        final String copyright = LyricsRequests.optString(lyrics, "copyright");
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
                final String entry = array.optString(i, "").trim();
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
