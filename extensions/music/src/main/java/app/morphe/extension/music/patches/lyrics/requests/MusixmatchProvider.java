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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.music.patches.lyrics.Lyrics;
import app.morphe.extension.music.patches.lyrics.LyricsLine;
import app.morphe.extension.music.patches.lyrics.TrackInfo;
import app.morphe.extension.music.patches.lyrics.Word;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.requests.Requester;

public final class MusixmatchProvider implements LyricsProvider {

    private static final String API_BASE = "https://apic.musixmatch.com/ws/1.1/";
    private static final String TOKEN_URL = API_BASE + "token.get";
    private static final String SEARCH_URL = API_BASE + "track.search";
    private static final String MACRO_URL = API_BASE + "macro.subtitles.get";
    private static final String APP_ID = "android-player-v1.0";
    private static final String USER_AGENT = "Dalvik/2.1.0 (Linux; U; Android 17)";
    private static final String COOKIE = "AWSELB=0; AWSELBCORS=0";

    private static final long MIN_WORD_MS = 40;
    private static final long BRIDGING_THRESHOLD_MS = 400;
    private static final long REQUEST_THROTTLE_MS = 500;

    private static final AtomicLong lastRequestTime = new AtomicLong(0);
    private static final Object TOKEN_LOCK = new Object();
    private static String cachedToken = null;
    private static String rejectedUserToken = null;

    @Override
    public String name() {
        return "Musixmatch";
    }

    @Override
    public boolean hasCandidates() {
        return true;
    }

    @Nullable
    @Override
    public FetchResult fetch(TrackInfo track) throws Exception {
        final List<Lyrics.ScoredLyrics> candidates = fetchCandidates(track);
        return candidates.isEmpty() ? null : FetchResult.of(candidates.get(0).lyrics(), track);
    }

    @Override
    public List<Lyrics.ScoredLyrics> fetchCandidates(TrackInfo track) throws Exception {
        final String token = ensureToken();
        if (token == null) {
            return Collections.emptyList();
        }

        final List<JSONObject> trackObjs;
        try {
            trackObjs = searchTracks(track, token);
        } catch (Exception ex) {
            Logger.printDebug(() -> "Could not search Musixmatch tracks", ex);
            return Collections.emptyList();
        }
        if (trackObjs.isEmpty()) {
            return Collections.emptyList();
        }

        final List<Lyrics.ScoredLyrics> scored = new ArrayList<>();
        Lyrics firstAccepted = null;
        int firstAcceptedScore = -1;
        for (JSONObject trackObj : trackObjs) {
            final int trackId = trackObj.optInt("track_id", -1);
            if (trackId <= 0) continue;
            final Lyrics lyrics;
            try {
                lyrics = fetchLyricsByTrackId(trackId, token);
            } catch (Exception ex) {
                Logger.printDebug(() -> "Could not fetch Musixmatch lyrics by track ID", ex);
                continue;
            }
            if (lyrics != null) {
                int score = LyricsRequests.scoreLyricsCandidate(
                        trackObj.optString("track_name", ""),
                        trackObj.optString("artist_name", ""),
                        trackObj.optInt("track_length", 0),
                        lyrics, track);
                if (firstAccepted == null) {
                    firstAccepted = lyrics;
                    firstAcceptedScore = score;
                }
                if (score >= LyricsRequests.SOFT_MIN + LyricsRequests.syncRank(lyrics)) {
                    scored.add(new Lyrics.ScoredLyrics(score, lyrics));
                    break;
                }
            }
        }
        if (scored.isEmpty() && firstAccepted != null) {
            scored.add(new Lyrics.ScoredLyrics(firstAcceptedScore, firstAccepted));
        }

        return Lyrics.sortScoredByScore(scored);
    }

    @Nullable
    private String ensureToken() throws IOException, JSONException {
        synchronized (TOKEN_LOCK) {
            final String userToken = Settings.MUSIXMATCH_TOKEN.get();
            if (!userToken.trim().isEmpty() && isUsableToken(userToken)
                    && !userToken.equals(rejectedUserToken)) {
                cachedToken = userToken;
                return cachedToken;
            }

            if (cachedToken != null && !cachedToken.isEmpty()) {
                return cachedToken;
            }

            final String url = TOKEN_URL
                    + "?user_language=en"
                    + "&app_id=" + APP_ID
                    + "&t=" + requestId();

            final HttpURLConnection connection = openApi(url);
            try {
                final int httpCode = connection.getResponseCode();
                if (httpCode != 200) {
                    return null;
                }

                JSONObject root = Requester.parseJSONObject(connection);
                final int status = headerStatus(root);
                if (status != 200) {
                    return null;
                }

                JSONObject body = obj(obj(root, "message"), "body");
                if (body != null) {
                    final String t = LyricsRequests.optString(body, "user_token");
                    if (isUsableToken(t)) {
                        cachedToken = t;
                    }
                }
                return cachedToken;
            } finally {
                connection.disconnect();
            }
        }
    }

    private List<JSONObject> searchTracks(TrackInfo track, String token)
            throws IOException, JSONException {
        final double durationSec = track.durationSeconds() > 0
                ? (double) track.durationSeconds() : 0;

        final StringBuilder url = new StringBuilder(SEARCH_URL)
                .append("?page_size=10&page=1&s_track_rating=desc")
                .append("&q_track=").append(LyricsRequests.encode(track.title()))
                .append("&q_artist=").append(LyricsRequests.encode(track.artist()))
                .append("&usertoken=").append(LyricsRequests.encode(token))
                .append("&format=json")
                .append("&app_id=" + APP_ID)
                .append("&t=" + requestId());

        if (durationSec > 0) {
            url.append("&q_duration=").append((int) durationSec);
        }

        LyricsRequests.throttle(lastRequestTime, REQUEST_THROTTLE_MS);
        final HttpURLConnection connection = openApi(url.toString());
        try {
            final int httpCode = connection.getResponseCode();
            if (httpCode != 200) {
                return Collections.emptyList();
            }

            JSONObject root = Requester.parseJSONObject(connection);
            final int status = headerStatus(root);
            final String hint = headerHint(root);
            if (status == 401) {
                rejectToken(hint);
                return Collections.emptyList();
            }
            if (status != 200) {
                return Collections.emptyList();
            }

            JSONObject body = obj(obj(root, "message"), "body");
            final JSONArray trackList = body != null ? body.optJSONArray("track_list") : null;
            if (trackList == null || trackList.length() == 0) {
                return Collections.emptyList();
            }

            final List<JSONObject> result = new ArrayList<>();
            for (int i = 0; i < trackList.length(); i++) {
                JSONObject item = trackList.optJSONObject(i);
                JSONObject trackObj = item != null ? item.optJSONObject("track") : null;
                if (trackObj != null) {
                    final int id = trackObj.optInt("track_id", -1);
                    if (id > 0) {
                        result.add(trackObj);
                    }
                }
            }
            return result;
        } finally {
            connection.disconnect();
        }
    }

    private static final Pattern LRC_LINE_PATTERN =
            Pattern.compile("\\[(\\d{2}):(\\d{2})\\.\\d{2}]\\s?(.*)");

    @Nullable
    private Lyrics fetchLyricsByTrackId(int trackId, String token)
            throws IOException, JSONException {

        final String url = MACRO_URL
                + "?namespace=lyrics_richsynched"
                + "&optional_calls=track.richsync"
                + "&subtitle_format=lrc"
                + "&track_id=" + trackId
                + "&f_subtitle_length_max_deviation=40"
                + "&usertoken=" + LyricsRequests.encode(token)
                + "&format=json"
                + "&app_id=" + APP_ID
                + "&t=" + requestId();

        LyricsRequests.throttle(lastRequestTime, REQUEST_THROTTLE_MS);
        final HttpURLConnection connection = openApi(url);
        try {
            final int httpCode = connection.getResponseCode();
            if (httpCode != 200) {
                return null;
            }

            JSONObject root = Requester.parseJSONObject(connection);
            final int status = headerStatus(root);
            final String hint = headerHint(root);
            if (status == 401) {
                rejectToken(hint);
                return null;
            }
            if (status != 200) {
                return null;
            }

            JSONObject message = obj(root, "message");
            JSONObject messageBody = obj(message, "body");
            JSONObject macroCalls = messageBody != null
                    ? messageBody.optJSONObject("macro_calls") : null;
            if (macroCalls == null) {
                return null;
            }

            final String copyright = extractCopyright(macroCalls);
            final String sourceUrl = extractBacklinkUrl(macroCalls);

            JSONObject richMsg = obj(obj(macroCalls, "track.richsync.get"), "message");
            JSONObject richHeader = obj(richMsg, "header");
            final int richStatus = richHeader != null ? richHeader.optInt("status_code", 0) : 0;
            if (richHeader != null && richStatus == 200) {
                JSONObject richBody = obj(obj(richMsg, "body"), "richsync");
                if (richBody != null) {
                    final String richBodyStr = LyricsRequests.optString(richBody, "richsync_body");
                    if (richBodyStr != null && !richBodyStr.isEmpty()) {
                        return appendCopyright(parseRichsync(richBodyStr, sourceUrl), copyright);
                    }
                }
            }

            JSONObject subMsg = obj(obj(macroCalls, "track.subtitles.get"), "message");
            JSONObject subHeader = obj(subMsg, "header");
            final int subStatus = subHeader != null ? subHeader.optInt("status_code", 0) : 0;
            if (subHeader != null && subStatus == 200) {
                JSONObject subBody = obj(subMsg, "body");
                final JSONArray subList = subBody != null
                        ? subBody.optJSONArray("subtitle_list") : null;
                if (subList != null && subList.length() > 0) {
                    JSONObject subItem = subList.optJSONObject(0);
                    JSONObject subtitle = subItem != null
                            ? subItem.optJSONObject("subtitle") : null;
                    if (subtitle != null) {
                        final String subtitleBody = LyricsRequests.optString(subtitle, "subtitle_body");
                        if (subtitleBody != null && !subtitleBody.isEmpty()) {
                            return appendCopyright(parseSubtitles(subtitleBody, sourceUrl), copyright);
                        }
                    }
                }
            }

            JSONObject lyricsMsg = obj(obj(macroCalls, "track.lyrics.get"), "message");
            JSONObject lyricsHeader = obj(lyricsMsg, "header");
            final int lyricsStatus = lyricsHeader != null ? lyricsHeader.optInt("status_code", 0) : 0;
            if (lyricsHeader != null && lyricsStatus == 200) {
                JSONObject lyricsBody = obj(obj(lyricsMsg, "body"), "lyrics");
                if (lyricsBody != null) {
                    final String lyricsText = LyricsRequests.optString(lyricsBody, "lyrics_body");
                    if (lyricsText != null && !lyricsText.isEmpty()) {
                        return appendCopyright(parseLyrics(lyricsText, sourceUrl), copyright);
                    }
                }
            }

            return null;
        } finally {
            connection.disconnect();
        }
    }

    @Nullable
    private static String extractCopyright(JSONObject macro) {
        JSONObject lyricsMsg = obj(obj(macro, "track.lyrics.get"), "message");
        JSONObject lyricsBody = obj(obj(lyricsMsg, "body"), "lyrics");
        if (lyricsBody != null) {
            final String c = LyricsRequests.optString(lyricsBody, "lyrics_copyright");
            if (c != null && !c.trim().isEmpty()) {
                return c.trim();
            }
        }
        JSONObject subMsg = obj(obj(macro, "track.subtitles.get"), "message");
        JSONObject subBody = obj(subMsg, "body");
        final JSONArray subList = subBody != null ? subBody.optJSONArray("subtitle_list") : null;
        if (subList != null && subList.length() > 0) {
            JSONObject subItem = subList.optJSONObject(0);
            JSONObject subtitle = subItem != null ? subItem.optJSONObject("subtitle") : null;
            if (subtitle != null) {
                final String c = LyricsRequests.optString(subtitle, "lyrics_copyright");
                if (c != null && !c.trim().isEmpty()) {
                    return c.trim();
                }
            }
        }
        JSONObject richMsg = obj(obj(macro, "track.richsync.get"), "message");
        JSONObject richBody = obj(obj(richMsg, "body"), "richsync");
        if (richBody != null) {
            final String c = LyricsRequests.optString(richBody, "lyrics_copyright");
            if (c != null && !c.trim().isEmpty()) {
                return c.trim();
            }
        }
        return null;
    }

    @Nullable
    private static String extractBacklinkUrl(JSONObject macro) {
        JSONObject lyricsMsg = obj(obj(macro, "track.lyrics.get"), "message");
        JSONObject lyricsBody = obj(obj(lyricsMsg, "body"), "lyrics");
        if (lyricsBody != null) {
            final String url = LyricsRequests.optString(lyricsBody, "backlink_url");
            if (url != null && !url.isEmpty()) {
                return stripTrackingParams(url);
            }
        }
        return null;
    }

    @Nullable
    private static Lyrics appendCopyright(@Nullable Lyrics lyrics, @Nullable String copyright) {
        if (lyrics == null || copyright == null || copyright.isEmpty()) {
            return lyrics;
        }
        final List<String> songwriters = new ArrayList<>();
        if (lyrics.songwriters() != null) {
            songwriters.addAll(lyrics.songwriters());
        }
        songwriters.add(copyright);
        return new Lyrics(lyrics.lines(), lyrics.providerName(), lyrics.synced(), null,
                lyrics.translations(), null, songwriters, lyrics.rawFormat(),
                lyrics.formatType(), lyrics.sourceUrl());
    }

    private static String stripTrackingParams(String url) {
        final int queryStart = url.indexOf('?');
        if (queryStart < 0) return url;
        final String base = url.substring(0, queryStart);
        final String query = url.substring(queryStart + 1);
        final StringBuilder filtered = new StringBuilder();
        for (String param : query.split("&")) {
            if (!param.toLowerCase().startsWith("utm")) {
                if (filtered.length() > 0) filtered.append('&');
                filtered.append(param);
            }
        }
        return filtered.length() > 0 ? base + "?" + filtered : base;
    }

    @Nullable
    private Lyrics parseRichsync(String body, @Nullable String sourceUrl)
            throws JSONException {
        final JSONArray lines = new JSONArray(body);
        if (lines.length() == 0) {
            return null;
        }
        final List<LyricsLine> result = new ArrayList<>();
        for (int i = 0; i < lines.length(); i++) {
            JSONObject line = lines.optJSONObject(i);
            if (line == null) {
                continue;
            }
            final double lineTs = line.optDouble("ts", 0);
            final double lineTe = line.optDouble("te", lineTs);
            final long lineStartMs = (long) (lineTs * 1000);
            final long lineEndMs = (long) (lineTe * 1000);
            final String x = LyricsRequests.optString(line, "x");

            List<Word> words = null;
            final JSONArray lArr = line.optJSONArray("l");
            if (lArr != null && lArr.length() > 0) {
                words = new ArrayList<>();
                for (int j = 0; j < lArr.length(); j++) {
                    JSONObject w = lArr.optJSONObject(j);
                    if (w == null) {
                        continue;
                    }
                    final String chunk = LyricsRequests.optString(w, "c");
                    if (chunk == null || chunk.isEmpty()) {
                        continue;
                    }
                    final double offset = w.optDouble("o", 0);
                    final long ws = (long) ((lineTs + offset) * 1000);

                    long we;
                    final int nextIdx = j + 1;
                    if (nextIdx < lArr.length()) {
                        JSONObject nextW = lArr.optJSONObject(nextIdx);
                        if (nextW != null) {
                            final double nextOffset = nextW.optDouble("o", offset);
                            final long nextWs = (long) ((lineTs + nextOffset) * 1000);
                            if (nextWs - ws <= BRIDGING_THRESHOLD_MS) {
                                we = nextWs;
                            } else {
                                we = ws;
                            }
                        } else {
                            we = lineEndMs;
                        }
                    } else {
                        we = lineEndMs;
                    }
                    if (we <= ws) {
                        we = ws + MIN_WORD_MS;
                    } else if (we - ws < MIN_WORD_MS) {
                        we = ws + MIN_WORD_MS;
                    }
                    words.add(new Word(ws, we, chunk));
                }
            }

            final String text;
            if (words != null && !words.isEmpty()) {
                text = joinWords(words);
            } else {
                text = x == null ? "" : x;
            }
            if (text.isEmpty()) {
                continue;
            }
            result.add(new LyricsLine(lineStartMs, text, words == null ? new ArrayList<>() : words));
        }
        if (result.isEmpty()) {
            return null;
        }
        return new Lyrics(result, name(), true, null, null, null, null, body, "mxm.json",
                sourceUrl);
    }

    @Nullable
    private Lyrics parseSubtitles(String body, @Nullable String sourceUrl)
            throws JSONException {
        String trimmed = body.trim();
        if (trimmed.startsWith("[") && trimmed.length() > 1
                && Character.isDigit(trimmed.charAt(1))) {
            return parseSubtitlesLrc(body, sourceUrl);
        }
        return parseSubtitlesJson(body, sourceUrl);
    }

    @Nullable
    private Lyrics parseSubtitlesLrc(String body, @Nullable String sourceUrl) {
        final List<LyricsLine> result = new ArrayList<>();
        for (String raw : body.split("\\n")) {
            final String line = raw.trim();
            if (line.isEmpty()) continue;
            final Matcher m = LRC_LINE_PATTERN.matcher(line);
            if (!m.matches()) continue;
            final int min = Integer.parseInt(m.group(1));
            final int sec = Integer.parseInt(m.group(2));
            final String text = m.group(3);
            if (text == null || text.isEmpty()) continue;
            final long startMs = min * 60_000L + sec * 1_000L;
            result.add(new LyricsLine(startMs, text));
        }
        if (result.isEmpty()) return null;
        return new Lyrics(result, name(), true, null, null, null, null, body, "lrc", sourceUrl);
    }

    @Nullable
    private Lyrics parseSubtitlesJson(String body, @Nullable String sourceUrl)
            throws JSONException {
        final JSONArray lines = new JSONArray(body);
        if (lines.length() == 0) {
            return null;
        }
        final List<LyricsLine> result = new ArrayList<>();
        for (int i = 0; i < lines.length(); i++) {
            JSONObject line = lines.optJSONObject(i);
            if (line == null) {
                continue;
            }
            JSONObject time = line.optJSONObject("time");
            final double total = time != null ? time.optDouble("total", 0) : 0;
            final long startMs = (long) (total * 1000);
            String text = line.optString("text", "\u266A");
            if (text.isEmpty()) {
                text = "\u266A";
            }
            result.add(new LyricsLine(startMs, text));
        }
        if (result.isEmpty()) {
            return null;
        }
        return new Lyrics(result, name(), true, null, null, null, null, null, "lrc", sourceUrl);
    }

    @Nullable
    private Lyrics parseLyrics(String body, @Nullable String sourceUrl) {
        final List<LyricsLine> result = LyricsRequests.parsePlainTextLines(body);
        if (result.isEmpty()) {
            return null;
        }
        return new Lyrics(result, name(), false, null, null, null, null, null, null, sourceUrl);
    }

    private HttpURLConnection openApi(String url) throws IOException {
        final HttpURLConnection connection = LyricsRequests.openConnection(url);
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.setRequestProperty("Cookie", COOKIE);
        return connection;
    }

    private static String requestId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static void rejectToken(String hint) {
        synchronized (TOKEN_LOCK) {
            if ("renew".equalsIgnoreCase(hint)
                    && cachedToken != null
                    && cachedToken.equals(Settings.MUSIXMATCH_TOKEN.get())) {
                rejectedUserToken = cachedToken;
            }
            cachedToken = null;
        }
    }

    public static void invalidateToken() {
        synchronized (TOKEN_LOCK) {
            cachedToken = null;
            rejectedUserToken = null;
        }
    }

    private static boolean isUsableToken(String token) {
        if (token == null || token.isEmpty() || "null".equals(token)) {
            return false;
        }
        for (int i = 0; i < token.length(); i++) {
            if (token.charAt(i) != '0') {
                return true;
            }
        }
        return false;
    }

    private static int headerStatus(JSONObject root) {
        JSONObject header = obj(obj(root, "message"), "header");
        return header == null ? 200 : header.optInt("status_code", 200);
    }

    @Nullable
    private static String headerHint(JSONObject root) {
        JSONObject header = obj(obj(root, "message"), "header");
        return header != null ? LyricsRequests.optString(header, "hint") : null;
    }

    private static JSONObject obj(JSONObject o, String key) {
        return o == null ? null : o.optJSONObject(key);
    }

    public static boolean validateToken(String token) {
        if (!isUsableToken(token)) return false;
        try {
            final String url = SEARCH_URL
                    + "?page_size=1&page=1&q_track=a&q_artist=a"
                    + "&usertoken=" + LyricsRequests.encode(token)
                    + "&format=json"
                    + "&app_id=" + APP_ID
                    + "&t=" + requestId();
            final HttpURLConnection connection = LyricsRequests.openConnection(url);
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setRequestProperty("Cookie", COOKIE);
            final int httpCode = connection.getResponseCode();
            if (httpCode != 200) {
                connection.disconnect();
                return false;
            }
            JSONObject root = Requester.parseJSONObject(connection);
            final int status = headerStatus(root);
            final String hint = headerHint(root);
            connection.disconnect();
            return status != 401 || !"renew".equalsIgnoreCase(hint);
        } catch (Exception ex) {
            Logger.printDebug(() -> "Could not validate Musixmatch token", ex);
            return true;
        }
    }

    private static String joinWords(List<Word> words) {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < words.size(); i++) {
            final String t = words.get(i).text();
            if (i > 0 && !t.isEmpty() && !t.startsWith(" ")
                    && sb.length() > 0 && sb.charAt(sb.length() - 1) != ' ') {
                sb.append(' ');
            }
            sb.append(t);
        }
        return sb.toString();
    }
}
