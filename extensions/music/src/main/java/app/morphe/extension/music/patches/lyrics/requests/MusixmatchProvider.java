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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import app.morphe.extension.music.settings.Settings;

import app.morphe.extension.music.patches.lyrics.Lyrics;
import app.morphe.extension.music.patches.lyrics.LyricsLine;
import app.morphe.extension.music.patches.lyrics.TrackInfo;
import app.morphe.extension.music.patches.lyrics.Word;
import app.morphe.extension.shared.requests.Requester;

public final class MusixmatchProvider implements LyricsProvider {

    private static final String API_BASE = "https://apic.musixmatch.com/ws/1.1/";
    private static final String TOKEN_URL = API_BASE + "token.get";
    private static final String SEARCH_URL = API_BASE + "track.search";
    private static final String MACRO_URL = API_BASE + "macro.subtitles.get";
    private static final String SUBTITLE_TRANSLATION_URL = API_BASE + "track.subtitle.translation.get";
    private static final String APP_ID = "android-player-v1.0";
    private static final String USER_AGENT = "Dalvik/2.1.0 (Linux; U; Android 17)";
    private static final String COOKIE = "AWSELB=0; AWSELBCORS=0";

    private static final long MIN_WORD_MS = 40;
    private static final long BRIDGING_THRESHOLD_MS = 400;
    private static final long REQUEST_THROTTLE_MS = 500;

    private static final AtomicLong lastRequestTime = new AtomicLong(0);
    private static String cachedToken = null;
    private static String[] cachedLanguages = null;

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
    public Lyrics fetch(TrackInfo track) throws Exception {
        final List<Lyrics> candidates = fetchCandidates(track);
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    @Override
    public List<Lyrics> fetchCandidates(TrackInfo track) throws Exception {
        final String token = ensureToken();
        if (token == null) {
            return java.util.Collections.emptyList();
        }

        final List<Integer> trackIds = searchTracks(track, token);
        if (trackIds.isEmpty()) {
            return java.util.Collections.emptyList();
        }

        final String lang = resolveLanguage();
        final List<Lyrics> results = new ArrayList<>();
        for (int trackId : trackIds) {
            final Lyrics lyrics = fetchLyricsByTrackId(track, trackId, token);
            if (lyrics != null) {
                if (!"en".equals(lang)) {
                    final Map<String, List<LyricsLine>> translations =
                            fetchTranslations(trackId, token, lang);
                    if (translations != null) {
                        results.add(new Lyrics(lyrics.lines(), lyrics.providerName(),
                                lyrics.synced(), null, translations, null, null,
                                lyrics.rawFormat(), lyrics.formatType(), lyrics.sourceUrl()));
                    } else {
                        results.add(lyrics);
                    }
                } else {
                    results.add(lyrics);
                }
            }
        }
        return results;
    }

    @Nullable
    private String ensureToken() throws IOException, JSONException {
        final String userToken = Settings.MUSIXMATCH_TOKEN.get();
        if (userToken != null && !userToken.isBlank() && isUsableToken(userToken)) {
            cachedToken = userToken;
            ensureLanguagesPopulated(cachedToken);
            return cachedToken;
        }

        if (cachedToken != null && !cachedToken.isEmpty()) {
            ensureLanguagesPopulated(cachedToken);
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

            final JSONObject root = Requester.parseJSONObject(connection);
            final int status = headerStatus(root);
            final String hint = headerHint(root);
            if (status == 401) {
                if ("captcha".equalsIgnoreCase(hint)) {
                    return null;
                }
            }
            if (status != 200) {
                return null;
            }

            final JSONObject body = obj(obj(root, "message"), "body");
            if (body != null) {
                final String t = body.optString("user_token", null);
                if (isUsableToken(t)) {
                    cachedToken = t;
                }
                final JSONObject appConfig = body.optJSONObject("app_config");
                if (appConfig != null) {
                    final JSONArray langs = appConfig.optJSONArray("languages");
                    if (langs != null && langs.length() > 0) {
                        cachedLanguages = new String[langs.length()];
                        for (int i = 0; i < langs.length(); i++) {
                            cachedLanguages[i] = langs.optString(i, null);
                        }
                    }
                }
            }
            return cachedToken;
        } finally {
            connection.disconnect();
        }
    }

    private void ensureLanguagesPopulated(String token) {
        if (cachedLanguages != null) return;
        try {
            final String url = TOKEN_URL
                    + "?user_language=en"
                    + "&app_id=" + APP_ID
                    + "&t=" + requestId();
            final HttpURLConnection connection = openApi(url);
            try {
                final int httpCode = connection.getResponseCode();
                if (httpCode != 200) {
                    return;
                }
                final JSONObject root = Requester.parseJSONObject(connection);
                final JSONObject body = obj(obj(root, "message"), "body");
                if (body == null) return;
                final JSONObject appConfig = body.optJSONObject("app_config");
                if (appConfig == null) return;
                final JSONArray langs = appConfig.optJSONArray("languages");
                if (langs == null || langs.length() == 0) return;
                cachedLanguages = new String[langs.length()];
                for (int i = 0; i < langs.length(); i++) {
                    cachedLanguages[i] = langs.optString(i, null);
                }
            } finally {
                connection.disconnect();
            }
        } catch (Exception ignored) {
        }
    }

    private String resolveLanguage() {
        final String sysLang = Locale.getDefault().getLanguage();
        if ("en".equals(sysLang)) {
            return "en";
        }
        if (cachedLanguages != null) {
            for (String supported : cachedLanguages) {
                if (sysLang.equals(supported)) {
                    return sysLang;
                }
            }
        }
        return "en";
    }

    private List<Integer> searchTracks(TrackInfo track, String token)
            throws IOException, JSONException {
        final double durationSec = track.durationSeconds() > 0
                ? (double) track.durationSeconds() : 0;

        final StringBuilder url = new StringBuilder(SEARCH_URL)
                .append("?page_size=10&page=1&s_track_rating=desc")
                .append("&q_track=").append(encode(track.title()))
                .append("&q_artist=").append(encode(track.artist()))
                .append("&usertoken=").append(encode(token))
                .append("&format=json")
                .append("&app_id=").append(APP_ID)
                .append("&t=").append(requestId());

        if (durationSec > 0) {
            url.append("&q_duration=").append((int) durationSec);
        }

        LyricsRequests.throttle(lastRequestTime, REQUEST_THROTTLE_MS);
        final HttpURLConnection connection = openApi(url.toString());
        try {
            final int httpCode = connection.getResponseCode();
            if (httpCode != 200) {
                return java.util.Collections.emptyList();
            }

            final JSONObject root = Requester.parseJSONObject(connection);
            final int status = headerStatus(root);
            final String hint = headerHint(root);
            if (status == 401) {
                if ("renew".equalsIgnoreCase(hint)) {
                    cachedToken = null;
                } else if ("captcha".equalsIgnoreCase(hint)) {
                    cachedToken = null;
                }
                return java.util.Collections.emptyList();
            }
            if (status != 200) {
                return java.util.Collections.emptyList();
            }

            final JSONObject body = obj(obj(root, "message"), "body");
            final JSONArray trackList = body != null ? body.optJSONArray("track_list") : null;
            if (trackList == null || trackList.length() == 0) {
                return java.util.Collections.emptyList();
            }

            final List<Integer> result = new ArrayList<>();
            for (int i = 0; i < trackList.length(); i++) {
                final JSONObject item = trackList.optJSONObject(i);
                final JSONObject trackObj = item != null ? item.optJSONObject("track") : null;
                if (trackObj != null) {
                    final int id = trackObj.optInt("track_id", -1);
                    if (id > 0) {
                        result.add(id);
                    }
                }
            }
            return result;
        } finally {
            connection.disconnect();
        }
    }

    @Nullable
    private Map<String, List<LyricsLine>> fetchTranslations(int trackId, String token,
            String language) throws IOException, JSONException {

        final StringBuilder url = new StringBuilder(SUBTITLE_TRANSLATION_URL)
                .append("?selected_language=").append(encode(language))
                .append("&track_id=").append(trackId)
                .append("&usertoken=").append(encode(token))
                .append("&format=json")
                .append("&app_id=").append(APP_ID)
                .append("&t=").append(requestId());

        LyricsRequests.throttle(lastRequestTime, REQUEST_THROTTLE_MS);
        final HttpURLConnection connection = openApi(url.toString());
        try {
            final int httpCode = connection.getResponseCode();
            if (httpCode != 200) {
                return null;
            }

            final JSONObject root = Requester.parseJSONObject(connection);
            final int status = headerStatus(root);
            final String hint = headerHint(root);
            if (status == 401) {
                if ("renew".equalsIgnoreCase(hint)) {
                    cachedToken = null;
                } else if ("captcha".equalsIgnoreCase(hint)) {
                    cachedToken = null;
                }
                return null;
            }
            if (status != 200) {
                return null;
            }

            final JSONObject body = obj(obj(root, "message"), "body");
            final JSONObject subtitle = body != null ? body.optJSONObject("subtitle") : null;
            if (subtitle == null) {
                return null;
            }
            final JSONObject translated = subtitle.optJSONObject("subtitle_translated");
            if (translated == null) {
                return null;
            }
            final String subtitleBody = translated.optString("subtitle_body", null);
            if (subtitleBody == null || subtitleBody.isEmpty()) {
                return null;
            }

            final List<LyricsLine> lines = parseTranslationLrc(subtitleBody);
            if (lines.isEmpty()) {
                return null;
            }
            final Map<String, List<LyricsLine>> result = new HashMap<>();
            result.put(language, lines);
            return result;
        } finally {
            connection.disconnect();
        }
    }

    private static final java.util.regex.Pattern LRC_LINE_PATTERN =
            java.util.regex.Pattern.compile("\\[(\\d{2}):(\\d{2})\\.\\d{2}]\\s?(.*)");

    private static List<LyricsLine> parseTranslationLrc(String lrcBody) {
        final List<LyricsLine> lines = new ArrayList<>();
        final String[] parts = lrcBody.split("\\n");
        for (String part : parts) {
            final String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            final java.util.regex.Matcher m = LRC_LINE_PATTERN.matcher(trimmed);
            if (m.matches()) {
                final int min = Integer.parseInt(m.group(1));
                final int sec = Integer.parseInt(m.group(2));
                final String text = m.group(3).trim();
                if (!text.isEmpty()) {
                    final long timeMs = (min * 60L + sec) * 1000L;
                    lines.add(new LyricsLine(timeMs, text));
                }
            }
        }
        return lines;
    }

    @Nullable
    private Lyrics fetchLyricsByTrackId(TrackInfo track, int trackId, String token)
            throws IOException, JSONException {

        final StringBuilder url = new StringBuilder(MACRO_URL)
                .append("?namespace=lyrics_richsynched")
                .append("&optional_calls=track.richsync")
                .append("&subtitle_format=lrc")
                .append("&track_id=").append(trackId)
                .append("&f_subtitle_length_max_deviation=40")
                .append("&usertoken=").append(encode(token))
                .append("&format=json")
                .append("&app_id=").append(APP_ID)
                .append("&t=").append(requestId());

        LyricsRequests.throttle(lastRequestTime, REQUEST_THROTTLE_MS);
        final HttpURLConnection connection = openApi(url.toString());
        try {
            final int httpCode = connection.getResponseCode();
            if (httpCode != 200) {
                return null;
            }

            final JSONObject root = Requester.parseJSONObject(connection);
            final int status = headerStatus(root);
            final String hint = headerHint(root);
            if (status == 401) {
                if ("renew".equalsIgnoreCase(hint)) {
                    cachedToken = null;
                } else if ("captcha".equalsIgnoreCase(hint)) {
                    cachedToken = null;
                }
                return null;
            }
            if (status != 200) {
                return null;
            }

            final JSONObject message = obj(root, "message");
            final JSONObject messageBody = obj(message, "body");
            final JSONObject macroCalls = messageBody != null
                    ? messageBody.optJSONObject("macro_calls") : null;
            if (macroCalls == null) {
                return null;
            }

            final String copyright = extractCopyright(macroCalls);
            final String sourceUrl = extractBacklinkUrl(macroCalls);

            final JSONObject richMsg = obj(obj(macroCalls, "track.richsync.get"), "message");
            final JSONObject richHeader = obj(richMsg, "header");
            final int richStatus = richHeader != null ? richHeader.optInt("status_code", 0) : 0;
            if (richHeader != null && richStatus == 200) {
                final JSONObject richBody = obj(obj(richMsg, "body"), "richsync");
                if (richBody != null) {
                    final String richBodyStr = richBody.optString("richsync_body", null);
                    if (richBodyStr != null && !richBodyStr.isEmpty()) {
                        return appendCopyright(parseRichsync(richBodyStr, sourceUrl), copyright);
                    }
                }
            }

            final JSONObject subMsg = obj(obj(macroCalls, "track.subtitles.get"), "message");
            final JSONObject subHeader = obj(subMsg, "header");
            final int subStatus = subHeader != null ? subHeader.optInt("status_code", 0) : 0;
            if (subHeader != null && subStatus == 200) {
                final JSONObject subBody = obj(subMsg, "body");
                final JSONArray subList = subBody != null
                        ? subBody.optJSONArray("subtitle_list") : null;
                if (subList != null && subList.length() > 0) {
                    final JSONObject subItem = subList.optJSONObject(0);
                    final JSONObject subtitle = subItem != null
                            ? subItem.optJSONObject("subtitle") : null;
                    if (subtitle != null) {
                        final String subtitleBody = subtitle.optString("subtitle_body", null);
                        if (subtitleBody != null && !subtitleBody.isEmpty()) {
                            return appendCopyright(parseSubtitles(subtitleBody, sourceUrl), copyright);
                        }
                    }
                }
            }

            final JSONObject lyricsMsg = obj(obj(macroCalls, "track.lyrics.get"), "message");
            final JSONObject lyricsHeader = obj(lyricsMsg, "header");
            final int lyricsStatus = lyricsHeader != null ? lyricsHeader.optInt("status_code", 0) : 0;
            if (lyricsHeader != null && lyricsStatus == 200) {
                final JSONObject lyricsBody = obj(obj(lyricsMsg, "body"), "lyrics");
                if (lyricsBody != null) {
                    final String lyricsText = lyricsBody.optString("lyrics_body", null);
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
        final JSONObject lyricsMsg = obj(obj(macro, "track.lyrics.get"), "message");
        final JSONObject lyricsBody = obj(obj(lyricsMsg, "body"), "lyrics");
        if (lyricsBody != null) {
            final String c = lyricsBody.optString("lyrics_copyright", null);
            if (c != null && !c.trim().isEmpty()) {
                return c.trim();
            }
        }
        final JSONObject subMsg = obj(obj(macro, "track.subtitles.get"), "message");
        final JSONObject subBody = obj(subMsg, "body");
        final JSONArray subList = subBody != null ? subBody.optJSONArray("subtitle_list") : null;
        if (subList != null && subList.length() > 0) {
            final JSONObject subItem = subList.optJSONObject(0);
            final JSONObject subtitle = subItem != null ? subItem.optJSONObject("subtitle") : null;
            if (subtitle != null) {
                final String c = subtitle.optString("lyrics_copyright", null);
                if (c != null && !c.trim().isEmpty()) {
                    return c.trim();
                }
            }
        }
        final JSONObject richMsg = obj(obj(macro, "track.richsync.get"), "message");
        final JSONObject richBody = obj(obj(richMsg, "body"), "richsync");
        if (richBody != null) {
            final String c = richBody.optString("lyrics_copyright", null);
            if (c != null && !c.trim().isEmpty()) {
                return c.trim();
            }
        }
        return null;
    }

    @Nullable
    private static String extractBacklinkUrl(JSONObject macro) {
        final JSONObject lyricsMsg = obj(obj(macro, "track.lyrics.get"), "message");
        final JSONObject lyricsBody = obj(obj(lyricsMsg, "body"), "lyrics");
        if (lyricsBody != null) {
            final String url = lyricsBody.optString("backlink_url", null);
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
            final JSONObject line = lines.optJSONObject(i);
            if (line == null) {
                continue;
            }
            final double lineTs = line.optDouble("ts", 0);
            final double lineTe = line.optDouble("te", lineTs);
            final long lineStartMs = (long) (lineTs * 1000);
            final long lineEndMs = (long) (lineTe * 1000);
            final String x = line.optString("x", null);

            List<Word> words = null;
            final JSONArray lArr = line.optJSONArray("l");
            if (lArr != null && lArr.length() > 0) {
                words = new ArrayList<>();
                for (int j = 0; j < lArr.length(); j++) {
                    final JSONObject w = lArr.optJSONObject(j);
                    if (w == null) {
                        continue;
                    }
                    final String chunk = w.optString("c", null);
                    if (chunk == null || chunk.isEmpty()) {
                        continue;
                    }
                    final double offset = w.optDouble("o", 0);
                    final long ws = (long) ((lineTs + offset) * 1000);

                    long we;
                    final int nextIdx = j + 1;
                    if (nextIdx < lArr.length()) {
                        final JSONObject nextW = lArr.optJSONObject(nextIdx);
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
        final JSONArray lines = new JSONArray(body);
        if (lines.length() == 0) {
            return null;
        }
        final List<LyricsLine> result = new ArrayList<>();
        for (int i = 0; i < lines.length(); i++) {
            final JSONObject line = lines.optJSONObject(i);
            if (line == null) {
                continue;
            }
            final JSONObject time = line.optJSONObject("time");
            final double total = time != null ? time.optDouble("total", 0) : 0;
            final long startMs = (long) (total * 1000);
            String text = line.optString("text", "♪");
            if (text.isEmpty()) {
                text = "♪";
            }
            result.add(new LyricsLine(startMs, text));
        }
        if (result.isEmpty()) {
            return null;
        }
        return new Lyrics(result, name(), true, null, null, null, null, null, null, sourceUrl);
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
        final JSONObject header = obj(obj(root, "message"), "header");
        return header == null ? 200 : header.optInt("status_code", 200);
    }

    @Nullable
    private static String headerHint(JSONObject root) {
        final JSONObject header = obj(obj(root, "message"), "header");
        return header != null ? header.optString("hint", null) : null;
    }

    private static JSONObject obj(JSONObject o, String key) {
        return o == null ? null : o.optJSONObject(key);
    }

    private static String encode(String s) {
        if (s == null) return "";
        final byte[] utf8 = s.getBytes(StandardCharsets.UTF_8);
        final StringBuilder sb = new StringBuilder(utf8.length * 3);
        for (byte b : utf8) {
            sb.append(String.format("%%%02X", b & 0xFF));
        }
        return sb.toString();
    }

    public static boolean validateToken(String token) {
        if (!isUsableToken(token)) return false;
        try {
            final String url = SEARCH_URL
                    + "?page_size=1&page=1&q_track=a&q_artist=a"
                    + "&usertoken=" + encode(token)
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
            final JSONObject root = Requester.parseJSONObject(connection);
            final int status = headerStatus(root);
            final String hint = headerHint(root);
            connection.disconnect();
            return status == 200
                    && !"renew".equalsIgnoreCase(hint)
                    && !"captcha".equalsIgnoreCase(hint);
        } catch (Exception ex) {
            return false;
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
