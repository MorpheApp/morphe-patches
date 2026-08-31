/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2269
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.requests;

import android.util.Base64;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.music.patches.lyrics.Lyrics;
import app.morphe.extension.music.patches.lyrics.LyricsLine;
import app.morphe.extension.music.patches.lyrics.TrackInfo;
import app.morphe.extension.music.patches.lyrics.Word;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.requests.Requester;

/**
 * NetEase Cloud Music lyrics. Uses the EAPI endpoint, which exposes word-synced
 * (YRC) lyrics when available, otherwise line-synced LRC. Translations and
 * romanizations are intentionally ignored.
 */
public final class NetEaseProvider implements LyricsProvider {

    private static final String EAPI_HOST = "https://interface.music.163.com";
    private static final String EAPI_KEY = "e82ckenh8dichen8";

    private static final String NETEASE_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; WOW64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Safari/537.36 Chrome/91.0.4472.164 "
            + "NeteaseMusicDesktop/3.1.3.203419";
    private static final String APP_VER = "3.1.3.203419";
    private static final String DEVICEID_XOR_KEY = "3go8&$8*3*3h0k(2)2";
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Safari/537.36 Chrome/91.0.4472.164 NeteaseMusicDesktop/3.1.3.203419";

    private static final String[] MOTHERBOARD_MODES = {
            "MS-iCraft B760M WIFI", "ASUS ROG STRIX Z790", "MSI MAG B550 TOMAHAWK",
            "ASRock X670E Taichi", "GIGABYTE Z790 AORUS ELITE"
    };

    private static final Random RANDOM = new Random();

    private static String deviceId = randomHex(32);
    private static String clientSign = generateClientSign();
    private static String osver = "Microsoft-Windows-10--build-" + (20000 + RANDOM.nextInt(10000)) + "-64bit";
    private static String mode = MOTHERBOARD_MODES[RANDOM.nextInt(MOTHERBOARD_MODES.length)];
    private static boolean initialized;
    private static final Map<String, String> cookieJar = new HashMap<>();

    private static final Pattern YRC_LINE = Pattern.compile("^\\[(\\d+),(\\d+)](.*)");
    private static final Pattern YRC_WORD = Pattern.compile("\\((\\d+),(\\d+),\\d+\\)([^()]*)");
    private static final Pattern LRC_TIME = Pattern.compile("\\[(\\d{1,}):(\\d{2})(?:[.:](\\d{1,3}))?]");
    private static final Pattern RICH_JSON = Pattern.compile("^\\s*\\{\"");

    @Override
    public String name() {
        return "NetEase";
    }

    @Nullable
    @Override
    public Lyrics fetch(TrackInfo track) throws Exception {
        String keyword = track.title() + " " + track.artist();
        JSONObject song = searchBest(keyword, track);
        if (song == null || !song.has("id")) {
            Logger.printDebug(() -> "NetEase no matching song for keyword='" + keyword + "'");
            return null;
        }

        JSONObject root = eapiRequest("/eapi/song/lyric/v1", new JSONObject()
                .put("id", song.getLong("id"))
                .put("lv", "-1")
                .put("tv", "-1")
                .put("rv", "-1")
                .put("yv", "-1"));

        String yrc = optLyric(root, "yrc");
        String lrc = optLyric(root, "lrc");
        String tlyric = optLyric(root, "tlyric");
        Logger.printDebug(() -> "NetEase lyric fields yrc=" + !yrc.isEmpty()
                + " lrc=" + !lrc.isEmpty() + " tlyric=" + !tlyric.isEmpty());
        List<LyricsLine> lines = parseNeteaseOriginalLyrics(yrc, lrc);
        if (lines.isEmpty()) {
            Logger.printDebug(() -> "NetEase parsed 0 lines (yrc len=" + yrc.length()
                    + " lrc len=" + lrc.length() + ") for " + track);
            return null;
        }

        Logger.printDebug(() -> "NetEase returned " + lines.size()
                + " lines (wordSynced=" + hasWordTimings(lines) + ") for " + track);
        return new Lyrics(lines, name(), true);
    }

    private static boolean hasWordTimings(List<LyricsLine> lines) {
        for (LyricsLine line : lines) {
            if (line.hasWords()) {
                return true;
            }
        }
        return false;
    }

    private static String optLyric(JSONObject root, String key) {
        JSONObject section = root.optJSONObject(key);
        return section == null ? "" : section.optString("lyric", "");
    }

    @Nullable
    private static JSONObject searchBest(String keyword, TrackInfo track) {
        List<JSONObject> candidates = new ArrayList<>();
        try {
            candidates.addAll(searchByEapi(keyword));
        } catch (Exception ex) {
            Logger.printDebug(() -> "NetEase EAPI search failed, trying cloudsearch: " + ex.getMessage());
            try {
                candidates.addAll(searchByCloudSearch(keyword));
            } catch (Exception ignored) {
            }
        }

        if (candidates.isEmpty()) {
            Logger.printDebug(() -> "NetEase search returned no candidates for keyword='" + keyword + "'");
            return null;
        }

        JSONObject best = null;
        int bestScore = -1;
        for (JSONObject candidate : candidates) {
            int score = scoreCandidate(candidate, track);
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best;
    }

    private static int scoreCandidate(JSONObject song, TrackInfo track) {
        String title = song.optString("name", "").toLowerCase(Locale.ROOT);
        String artist = song.optString("artist", "").toLowerCase(Locale.ROOT);
        String wantedTitle = track.title().toLowerCase(Locale.ROOT);
        String wantedArtist = track.artist().toLowerCase(Locale.ROOT);

        int score = 0;
        if (!title.isEmpty() && (title.contains(wantedTitle) || wantedTitle.contains(title))) {
            score += 2;
        }
        if (!artist.isEmpty() && artist.contains(wantedArtist)) {
            score += 2;
        }
        if (track.durationSeconds() > 0) {
            long duration = song.optLong("duration", 0);
            if (duration > 0 && Math.abs(duration / 1000 - track.durationSeconds()) <= 5) {
                score += 2;
            }
        }
        return score;
    }

    private static List<JSONObject> searchByEapi(String keyword) throws IOException, JSONException {
        JSONObject root = eapiRequest("/eapi/search/song/list/page", new JSONObject()
                .put("limit", "30")
                .put("offset", "0")
                .put("keyword", keyword)
                .put("scene", "NORMAL")
                .put("needCorrect", "true"));

        JSONArray resources = root.optJSONObject("data").optJSONArray("resources");
        List<JSONObject> songs = new ArrayList<>();
        if (resources == null) {
            return songs;
        }
        for (int i = 0; i < resources.length(); i++) {
            JSONObject resource = resources.optJSONObject(i);
            if (resource == null) {
                continue;
            }
            JSONObject simple = resource.optJSONObject("baseInfo").optJSONObject("simpleSongData");
            if (simple != null) {
                JSONObject mapped = mapSong(simple);
                if (mapped != null) {
                    songs.add(mapped);
                }
            }
        }
        return songs;
    }

    private static List<JSONObject> searchByCloudSearch(String keyword) throws IOException, JSONException {
        String form = "s=" + encode(keyword) + "&type=1&offset=0&limit=30";
        HttpURLConnection connection = LyricsRequests.postForm(
                "https://music.163.com/api/cloudsearch/pc", form);
        if (connection.getResponseCode() != 200) {
            LyricsRequests.logFailure("NetEase", connection);
            return new ArrayList<>();
        }

        JSONObject root = Requester.parseJSONObject(connection);
        JSONArray songs = root.optJSONObject("result").optJSONArray("songs");
        List<JSONObject> mapped = new ArrayList<>();
        if (songs == null) {
            return mapped;
        }
        for (int i = 0; i < songs.length(); i++) {
            JSONObject song = songs.optJSONObject(i);
            if (song != null) {
                JSONObject mappedSong = mapSong(song);
                if (mappedSong != null) {
                    mapped.add(mappedSong);
                }
            }
        }
        return mapped;
    }

    @Nullable
    private static JSONObject mapSong(JSONObject song) throws JSONException {
        if (song == null || !song.has("id")) {
            return null;
        }

        JSONObject mapped = new JSONObject();
        mapped.put("id", song.optLong("id"));
        mapped.put("name", song.optString("name", ""));

        JSONArray artists = song.optJSONArray("artists") != null
                ? song.optJSONArray("artists") : song.optJSONArray("ar");
        mapped.put("artist", joinArtists(artists));

        JSONObject album = song.optJSONObject("album") != null
                ? song.optJSONObject("album") : song.optJSONObject("al");
        mapped.put("album", album == null ? "" : album.optString("name", ""));

        long duration = song.optLong("duration", song.optLong("dt", 0));
        mapped.put("duration", duration);

        return mapped;
    }

    private static String joinArtists(JSONArray artists) {
        if (artists == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < artists.length(); i++) {
            JSONObject artist = artists.optJSONObject(i);
            if (artist == null) {
                continue;
            }
            String name = artist.optString("name", "");
            if (!name.isEmpty()) {
                if (builder.length() > 0) {
                    builder.append('/');
                }
                builder.append(name);
            }
        }
        return builder.toString();
    }

    private static void ensureInit() throws JSONException {
        if (initialized) {
            return;
        }
        resetPreCookies();
        try {
            String username = getAnonimousUsername(deviceId);
            JSONObject root = eapiRequestRaw("/eapi/register/anonimous",
                    new JSONObject().put("username", username).put("e_r", true));
            Logger.printDebug(() -> "NetEase anon login resp code=" + root.opt("code")
                    + " body=" + root.toString().substring(0, Math.min(root.toString().length(), 200)));
            if (!"200".equals(String.valueOf(root.opt("code")))) {
                throw new IOException("NetEase anonymous login failed: " + root);
            }
            if (!cookieJar.containsKey("WNMCID")) {
                cookieJar.put("WNMCID", randomLower(6) + "." + System.currentTimeMillis() + ".01.0");
            }
            initialized = true;
        } catch (Exception ex) {
            Logger.printInfo(() -> "NetEase anonymous login error", ex);
        }
    }

    private static void resetPreCookies() {
        cookieJar.clear();
        cookieJar.put("os", "pc");
        cookieJar.put("deviceId", deviceId);
        cookieJar.put("osver", osver);
        cookieJar.put("clientSign", clientSign);
        cookieJar.put("channel", "netease");
        cookieJar.put("mode", mode);
        cookieJar.put("appver", APP_VER);
    }

    private static JSONObject eapiRequest(String path, JSONObject params) throws IOException, JSONException {
        try {
            ensureInit();
        } catch (Exception ex) {
            if (cookieJar.isEmpty()) {
                resetPreCookies();
            }
        }

        String body = "params=" + buildEapiParams(path, params);
        Map<String, String> headers = new HashMap<>();
        headers.put("Referer", "https://music.163.com/");
        headers.put("User-Agent", NETEASE_USER_AGENT);
        headers.put("Accept", "*/*");
        headers.put("Host", "interface.music.163.com");
        String cookie = cookieHeader();
        if (!cookie.isEmpty()) {
            headers.put("Cookie", cookie);
        }
        HttpURLConnection connection = LyricsRequests.postForm(EAPI_HOST + path, body, headers);
        int httpCode = connection.getResponseCode();
        if (httpCode != 200) {
            LyricsRequests.logFailure("NetEase", connection);
            Logger.printDebug(() -> "NetEase " + path + " HTTP " + httpCode);
            return new JSONObject();
        }

        captureCookies(connection);

        String decrypted = decryptEapiResponse(connection);
        Logger.printDebug(() -> "NetEase " + path + " HTTP 200 decrypted len=" + decrypted.length()
                + " preview=" + decrypted.substring(0, Math.min(decrypted.length(), 200)));
        if (decrypted.isEmpty()) {
            return new JSONObject();
        }

        JSONObject root = new JSONObject(decrypted);
        String code = String.valueOf(root.opt("code"));
        if ("301".equals(code) || "401".equals(code)) {
            initialized = false;
            cookieJar.clear();
            throw new IOException("NetEase session invalid: " + decrypted);
        }
        return root;
    }

    private static JSONObject eapiRequestRaw(String path, JSONObject params) throws IOException, JSONException {
        String body = "params=" + buildEapiParams(path, params);
        Map<String, String> headers = new HashMap<>();
        headers.put("Referer", "https://music.163.com/");
        headers.put("User-Agent", NETEASE_USER_AGENT);
        headers.put("Accept", "*/*");
        headers.put("Host", "interface.music.163.com");
        String cookie = cookieHeader();
        if (!cookie.isEmpty()) {
            headers.put("Cookie", cookie);
        }
        HttpURLConnection connection = LyricsRequests.postForm(EAPI_HOST + path, body, headers);
        int httpCode = connection.getResponseCode();
        if (httpCode != 200) {
            LyricsRequests.logFailure("NetEase", connection);
            Logger.printDebug(() -> "NetEase " + path + " HTTP " + httpCode);
            return new JSONObject();
        }
        captureCookies(connection);
        String decrypted = decryptEapiResponse(connection);
        return decrypted.isEmpty() ? new JSONObject() : new JSONObject(decrypted);
    }

    private static String buildEapiParams(String path, JSONObject params) throws JSONException {
        JSONObject header = new JSONObject();
        header.put("clientSign", clientSign);
        header.put("osver", osver);
        header.put("deviceId", deviceId);
        header.put("os", "pc");
        header.put("appver", APP_VER);
        header.put("requestId", String.valueOf(System.currentTimeMillis()));

        params.put("header", header.toString());
        if (!params.has("e_r")) {
            params.put("e_r", true);
        }

        String actualPath = path.replace("/eapi/", "/api/");
        String paramsText = params.toString();
        String digest = LyricsCrypto.md5Hex(
                "nobody" + actualPath + "use" + paramsText + "md5forencrypt");
        String data = actualPath + "-36cd479b6b5-" + paramsText + "-36cd479b6b5-" + digest;
        return LyricsCrypto.aesEcbPkcs5EncryptHex(data, EAPI_KEY);
    }

    private static String decryptEapiResponse(HttpURLConnection connection) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = connection.getInputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
        }
        byte[] raw = out.toByteArray();
        String base64 = Base64.encodeToString(raw, Base64.NO_WRAP);
        String decrypted = LyricsCrypto.aesEcbPkcs5DecryptBase64ToString(base64, EAPI_KEY);
        if (decrypted.isEmpty()) {
            Logger.printDebug(() -> "NetEase " + connection.getURL() + " decrypted empty (raw bytes=" + raw.length + ")");
        }
        return decrypted;
    }

    private static String getAnonimousUsername(String deviceIdValue) {
        StringBuilder xored = new StringBuilder();
        for (int i = 0; i < deviceIdValue.length(); i++) {
            char left = deviceIdValue.charAt(i);
            char right = DEVICEID_XOR_KEY.charAt(i % DEVICEID_XOR_KEY.length());
            xored.append((char) (left ^ right));
        }
        byte[] md5 = LyricsCrypto.md5Bytes(xored.toString().getBytes(StandardCharsets.UTF_8));
        String base64Md5 = Base64.encodeToString(md5, Base64.NO_WRAP);
        String combined = deviceIdValue + " " + base64Md5;
        return Base64.encodeToString(combined.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
    }

    private static void captureCookies(HttpURLConnection connection) {
        Map<String, List<String>> headerFields = connection.getHeaderFields();
        List<String> setCookies = headerFields.get("Set-Cookie");
        if (setCookies == null) {
            setCookies = headerFields.get("set-cookie");
        }
        if (setCookies == null) {
            return;
        }
        for (String line : setCookies) {
            if (line == null) {
                continue;
            }
            String pair = line.split(";", 2)[0];
            int index = pair.indexOf('=');
            if (index <= 0) {
                continue;
            }
            String key = pair.substring(0, index).trim();
            String value = pair.substring(index + 1).trim();
            if (!key.isEmpty() && !value.isEmpty()) {
                cookieJar.put(key, value);
            }
        }
    }

    private static String cookieHeader() {
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, String> entry : cookieJar.entrySet()) {
            if (builder.length() > 0) {
                builder.append("; ");
            }
            builder.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return builder.toString();
    }

    private static List<LyricsLine> parseNeteaseOriginalLyrics(String yrc, String lrc) throws JSONException {
        if (yrc != null && !yrc.isEmpty()) {
            List<LyricsLine> lines = parseYrc(yrc);
            if (!lines.isEmpty()) {
                return lines;
            }
        }

        if (lrc != null && RICH_JSON.matcher(lrc).find()) {
            return parseMixedNeteaseLyrics(lrc);
        }

        return parseLrc(lrc);
    }

    private static List<LyricsLine> parseYrc(String text) throws JSONException {
        List<LyricsLine> lines = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return lines;
        }

        for (String rawLine : text.split("\\r?\\n")) {
            String line = rawLine.trim();
            Matcher lineMatch = YRC_LINE.matcher(line);
            if (!lineMatch.matches()) {
                continue;
            }

            long lineStart = Long.parseLong(lineMatch.group(1));
            long lineDuration = Long.parseLong(lineMatch.group(2));
            long lineEnd = lineStart + lineDuration;
            String content = lineMatch.group(3);

            List<Long> starts = new ArrayList<>();
            List<Long> durations = new ArrayList<>();
            List<String> texts = new ArrayList<>();
            Matcher wordMatch = YRC_WORD.matcher(content);
            while (wordMatch.find()) {
                starts.add(Long.parseLong(wordMatch.group(1)));
                durations.add(Long.parseLong(wordMatch.group(2)));
                texts.add(wordMatch.group(3));
            }

            List<Word> words = new ArrayList<>();
            StringBuilder full = new StringBuilder();
            for (int i = 0; i < starts.size(); i++) {
                long wordStart = starts.get(i);
                long wordEnd = wordStart + durations.get(i);
                String wordText = texts.get(i);
                words.add(new Word(wordStart, wordEnd, wordText));
                full.append(wordText);
            }
            if (words.isEmpty() && !content.isEmpty()) {
                words.add(new Word(lineStart, lineEnd, content));
                full.append(content);
            }
            if (words.isEmpty()) {
                continue;
            }

            String fullText = full.toString().trim();
            if (fullText.isEmpty()) {
                continue;
            }
            lines.add(new LyricsLine(lineStart, fullText, words));
        }

        lines.sort(Comparator.comparingLong(LyricsLine::startTimeMs));
        return lines;
    }

    private static List<LyricsLine> parseLrc(String text) throws JSONException {
        List<LyricsLine> lines = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return lines;
        }

        List<Item> items = new ArrayList<>();
        for (String rawLine : text.split("\\r?\\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            Matcher timeMatch = LRC_TIME.matcher(line);
            List<Long> starts = new ArrayList<>();
            int lastEnd = -1;
            while (timeMatch.find()) {
                starts.add(parseTimeMs(timeMatch.group(1), timeMatch.group(2), timeMatch.group(3)));
                lastEnd = timeMatch.end();
            }
            if (starts.isEmpty()) {
                continue;
            }
            String content = line.substring(lastEnd).trim();
            if (content.isEmpty()) {
                continue;
            }
            for (Long start : starts) {
                items.add(new Item(start, content));
            }
        }

        items.sort(Comparator.comparingLong(i -> i.start));
        for (int i = 0; i < items.size(); i++) {
            Item item = items.get(i);
            long end = (i < items.size() - 1)
                    ? Math.max(item.start, items.get(i + 1).start - 10)
                    : item.start + 3000;
            Word word = new Word(item.start, end, item.text);
            lines.add(new LyricsLine(item.start, item.text, List.of(word)));
        }
        return lines;
    }

    private static List<LyricsLine> parseMixedNeteaseLyrics(String text) throws JSONException {
        List<Item> items = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return new ArrayList<>();
        }

        for (String rawLine : text.split("\\r?\\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }

            if (line.charAt(0) == '{') {
                try {
                    JSONObject obj = new JSONObject(line);
                    long start = obj.optLong("t", 0);
                    JSONArray parts = obj.optJSONArray("c");
                    if (parts == null) {
                        continue;
                    }
                    StringBuilder builder = new StringBuilder();
                    for (int i = 0; i < parts.length(); i++) {
                        JSONObject part = parts.optJSONObject(i);
                        if (part != null) {
                            String tx = part.optString("tx", "");
                            if (!tx.isEmpty()) {
                                builder.append(tx);
                            }
                        }
                    }
                    String value = builder.toString().trim();
                    if (!value.isEmpty()) {
                        items.add(new Item(start, value));
                    }
                } catch (Exception ignored) {
                }
                continue;
            }

            Matcher timeMatch = LRC_TIME.matcher(line);
            List<Long> starts = new ArrayList<>();
            int lastEnd = -1;
            while (timeMatch.find()) {
                starts.add(parseTimeMs(timeMatch.group(1), timeMatch.group(2), timeMatch.group(3)));
                lastEnd = timeMatch.end();
            }
            if (starts.isEmpty()) {
                continue;
            }
            String content = line.substring(lastEnd).trim();
            if (content.isEmpty()) {
                continue;
            }
            for (Long start : starts) {
                items.add(new Item(start, content));
            }
        }

        items.sort(Comparator.comparingLong(i -> i.start));
        List<LyricsLine> lines = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            Item item = items.get(i);
            long end = (i < items.size() - 1)
                    ? Math.max(item.start, items.get(i + 1).start - 10)
                    : item.start + 3000;
            Word word = new Word(item.start, end, item.text);
            lines.add(new LyricsLine(item.start, item.text, List.of(word)));
        }
        return lines;
    }

    private static long parseTimeMs(String minutes, String seconds, String fraction) {
        long min = Long.parseLong(minutes);
        long sec = Long.parseLong(seconds);
        String fractionText = (fraction == null) ? "0" : fraction;
        while (fractionText.length() < 3) {
            fractionText += "0";
        }
        fractionText = fractionText.substring(0, 3);
        return (min * 60 + sec) * 1000 + Long.parseLong(fractionText);
    }

    private static final class Item {
        final long start;
        final String text;

        Item(long start, String text) {
            this.start = start;
            this.text = text;
        }
    }

    private static String randomHex(int length) {
        final String chars = "0123456789abcdef";
        StringBuilder builder = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            builder.append(chars.charAt(RANDOM.nextInt(chars.length())));
        }
        return builder.toString();
    }

    private static String randomLower(int length) {
        final String chars = "abcdefghijklmnopqrstuvwxyz";
        StringBuilder builder = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            builder.append(chars.charAt(RANDOM.nextInt(chars.length())));
        }
        return builder.toString();
    }

    private static String randomUpper(int length) {
        final String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
        StringBuilder builder = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            builder.append(chars.charAt(RANDOM.nextInt(chars.length())));
        }
        return builder.toString();
    }

    private static String randomMac() {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            if (i > 0) {
                builder.append(':');
            }
            builder.append(String.format("%02X", RANDOM.nextInt(256)));
        }
        return builder.toString();
    }

    private static String generateClientSign() {
        return randomMac() + "@@@" + randomUpper(8) + "@@@@@@" + randomHex(64);
    }

    @SuppressWarnings("CharsetObjectCanBeUsed")
    private static String encode(String value) throws IOException {
        return java.net.URLEncoder.encode(value, "UTF-8");
    }
}
