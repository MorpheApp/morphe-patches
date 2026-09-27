/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2625
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
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.music.patches.lyrics.Lyrics;
import app.morphe.extension.music.patches.lyrics.LyricsLine;
import app.morphe.extension.music.patches.lyrics.LyricsMerge;
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
    private static final String REGISTER_PATH = "/eapi/register/anonimous";

    private static final String NETEASE_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; WOW64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Safari/537.36 Chrome/91.0.4472.164 "
            + "NeteaseMusicDesktop/3.1.3.203419";
    private static final String APP_VER = "3.1.3.203419";
    private static final String DEVICEID_XOR_KEY = "3go8&$8*3*3h0k(2)2";

    private static final String[] MOTHERBOARD_MODES = {
            "MS-iCraft B760M WIFI", "ASUS ROG STRIX Z790", "MSI MAG B550 TOMAHAWK",
            "ASRock X670E Taichi", "GIGABYTE Z790 AORUS ELITE"
    };

    private static final Random RANDOM = new Random();

    private static final String deviceId = randomChars(32, "0123456789abcdef");
    private static final String clientSign = generateClientSign();
    private static final String osver = "Microsoft-Windows-10--build-" + (20000 + RANDOM.nextInt(10000)) + "-64bit";
    private static final String mode = MOTHERBOARD_MODES[RANDOM.nextInt(MOTHERBOARD_MODES.length)];
    private static boolean initialized;
    private static final Map<String, String> cookieJar = new HashMap<>();

    private static final Pattern YRC_LINE = Pattern.compile("^\\[(\\d+),(\\d+)](.*)");
    private static final Pattern YRC_WORD = Pattern.compile("\\((\\d+),(\\d+),\\d+\\)([^()]*)");
    private static final Pattern LRC_TIME = Pattern.compile("\\[(\\d+):(\\d{2})(?:[.:](\\d{1,3}))?]");
    private static final Pattern RICH_JSON = Pattern.compile("^\\s*\\{\"");

    @Override
    public String name() {
        return "NetEase";
    }

    @Override
    public boolean hasCandidates() {
        return true;
    }

    @Nullable
    @Override
    public FetchResult fetch(TrackInfo track) throws Exception {
        String keyword = track.title() + " " + track.artist();
        JSONObject song = searchBest(keyword, track);
        if (song == null || !song.has("id")) {
            return null;
        }
        Lyrics lyrics = fetchFromSong(song);
        if (lyrics == null) {
            return null;
        }
        final long durationMs = song.optLong("duration", 0);
        return FetchResult.of(lyrics,
                song.optString("name", ""),
                song.optString("artist", ""),
                durationMs > 0 ? durationMs / 1000 : 0,
                track);
    }

    @Override
    public List<Lyrics.ScoredLyrics> fetchCandidates(TrackInfo track) throws Exception {
        String keyword = track.title() + " " + track.artist();
        List<JSONObject> songs = searchAll(keyword, track);

        List<Lyrics.ScoredLyrics> scored = new ArrayList<>();
        for (JSONObject song : songs) {
            if (scored.size() >= LyricsRequests.MAX_CANDIDATES) {
                break;
            }
            if (song == null || !song.has("id")) {
                continue;
            }
            try {
                Lyrics lyrics = fetchFromSong(song);
                if (lyrics != null) {
                    final long durationMs = song.optLong("duration", 0);
                    int score = LyricsRequests.scoreLyricsCandidate(
                            song.optString("name", ""), song.optString("artist", ""),
                            durationMs > 0 ? durationMs / 1000 : 0, lyrics, track);
                    scored.add(new Lyrics.ScoredLyrics(score, lyrics));
                }
            } catch (Exception ex) {
                Logger.printDebug(() -> "Could not fetch NetEase lyrics for a song", ex);
            }
        }

        return Lyrics.sortScoredByScore(scored);
    }

    @Nullable
    private Lyrics fetchFromSong(JSONObject song) throws Exception {
        JSONObject root = eapiRequest("/eapi/song/lyric/v1", new JSONObject()
                .put("id", song.getLong("id"))
                .put("lv", "-1")
                .put("tv", "-1")
                .put("rv", "-1")
                .put("yv", "-1"));

        String yrc = optLyric(root, "yrc");
        String lrc = optLyric(root, "lrc");
        String tlyric = optLyric(root, "tlyric");
        String romalrc = optLyric(root, "romalrc");

        List<LyricsLine> lines = parseNeteaseOriginalLyrics(yrc, lrc);
        if (lines.isEmpty()) {
            return null;
        }

        List<String> creditLines = parseYrcCredits(yrc);

        List<LyricsLine> romaLines = romalrc.isEmpty() ? null : LrcParser.parseSynced(romalrc);
        List<LyricsLine> romanization = LyricsMerge.mergeRomanization(lines, romaLines);

        List<LyricsLine> transLines = tlyric.isEmpty() ? null : LrcParser.parseSynced(tlyric);
        List<LyricsLine> translation = LyricsMerge.mergeRomanization(lines, transLines);
        Map<String, List<LyricsLine>> translations =
                LyricsMerge.singleLanguageTranslations(translation, "zh");

        String rawFormat = filterJsonLines(!yrc.isEmpty() ? yrc : lrc);
        String formatType = !yrc.isEmpty() ? "yrc" : "lrc";
        long songId = song.getLong("id");
        String sourceUrl = "https://music.163.com/song?id=" + songId;
        return new Lyrics(lines, name(), true, romanization, translations, null,
                creditLines.isEmpty() ? null : creditLines, rawFormat, formatType, sourceUrl);
    }

    /** YRC files carry the credits as JSON lines mixed in with the timed lines. */
    private static List<String> parseYrcCredits(String yrc) {
        List<String> creditLines = new ArrayList<>();
        if (yrc.isEmpty()) {
            return creditLines;
        }
        for (String rawLine : yrc.split("\\r?\\n")) {
            String trimmed = rawLine.trim();
            if (!trimmed.startsWith("{")) {
                continue;
            }
            try {
                JSONObject obj = new JSONObject(trimmed);
                JSONArray parts = obj.optJSONArray("c");
                if (parts == null) {
                    continue;
                }
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < parts.length(); i++) {
                    JSONObject part = parts.optJSONObject(i);
                    if (part != null) {
                        String tx = part.optString("tx", "");
                        if (!tx.isEmpty()) {
                            sb.append(tx);
                        }
                    }
                }
                String value = sb.toString().trim();
                if (!value.isEmpty()) {
                    creditLines.add(value);
                }
            } catch (Exception ex) {
                Logger.printDebug(() -> "Could not parse NetEase user description", ex);
            }
        }
        return creditLines;
    }

    private static String optLyric(JSONObject root, String key) {
        JSONObject section = root.optJSONObject(key);
        return section == null ? "" : section.optString("lyric", "");
    }

    private static String filterJsonLines(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        StringBuilder builder = new StringBuilder(text.length());
        for (String rawLine : text.split("\\r?\\n")) {
            String trimmed = rawLine.trim();
            if (trimmed.startsWith("{")) {
                continue;
            }
            //noinspection SizeReplaceableByIsEmpty
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(rawLine);
        }
        return builder.toString();
    }

    private static List<JSONObject> searchCandidates(String keyword) {
        List<JSONObject> candidates = new ArrayList<>();
        try {
            candidates.addAll(searchByEapi(keyword));
        } catch (Exception ex) {
            Logger.printDebug(() -> "Could not search NetEase by EAPI", ex);
            try {
                candidates.addAll(searchByCloudSearch(keyword));
            } catch (Exception ex2) {
                Logger.printDebug(() -> "Could not search NetEase by CloudSearch", ex2);
            }
        }
        return candidates;
    }

    private static List<JSONObject> searchAll(String keyword, TrackInfo track) {
        List<JSONObject> candidates = searchCandidates(keyword);
        if (candidates.isEmpty()) {
            return new ArrayList<>();
        }
        List<JSONObject> scored = new ArrayList<>(candidates);
        scored.sort((a, b) -> scoreCandidate(b, track) - scoreCandidate(a, track));
        List<JSONObject> passed = new ArrayList<>();
        for (JSONObject item : scored) {
            if (scoreCandidate(item, track) >= LyricsRequests.SOFT_MIN) {
                passed.add(item);
            }
        }
        return passed.isEmpty() && !scored.isEmpty()
                ? List.of(scored.get(0)) : passed;
    }

    @Nullable
    private static JSONObject searchBest(String keyword, TrackInfo track) {
        List<JSONObject> candidates = searchCandidates(keyword);
        if (candidates.isEmpty()) {
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
        if (best == null || bestScore < LyricsRequests.SOFT_MIN) {
            return null;
        }
        return best;
    }

    private static int scoreCandidate(JSONObject song, TrackInfo track) {
        String title = song.optString("name", "");
        String artist = song.optString("artist", "");
        final long durationMs = song.optLong("duration", 0);
        return LyricsRequests.scoreTrackCandidate(title, artist,
                durationMs > 0 ? durationMs / 1000 : 0, track);
    }

    private static List<JSONObject> searchByEapi(String keyword) throws IOException, JSONException {
        JSONObject root = eapiRequest("/eapi/search/song/list/page", new JSONObject()
                .put("limit", "30")
                .put("offset", "0")
                .put("keyword", keyword)
                .put("scene", "NORMAL")
                .put("needCorrect", "true"));

        JSONObject data = root.optJSONObject("data");
        JSONArray resources = data == null ? null : data.optJSONArray("resources");
        List<JSONObject> songs = new ArrayList<>();
        if (resources == null) {
            return songs;
        }
        for (int i = 0; i < resources.length(); i++) {
            JSONObject resource = resources.optJSONObject(i);
            if (resource == null) {
                continue;
            }
            JSONObject simple = LyricsRequests.optPath(resource, "baseInfo", "simpleSongData");
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
        String form = "s=" + LyricsRequests.encode(keyword) + "&type=1&offset=0&limit=30";
        HttpURLConnection connection = LyricsRequests.postForm(
                "https://music.163.com/api/cloudsearch/pc", form);
        if (connection.getResponseCode() != Requester.HTTP_STATUS_CODE_SUCCESS) {
            LyricsRequests.logFailure("NetEase", connection);
            return new ArrayList<>();
        }

        JSONObject root = Requester.parseJSONObject(connection);
        JSONObject result = root.optJSONObject("result");
        JSONArray songs = result == null ? null : result.optJSONArray("songs");
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
                //noinspection SizeReplaceableByIsEmpty
                if (builder.length() > 0) {
                    builder.append('/');
                }
                builder.append(name);
            }
        }
        return builder.toString();
    }

    private static void ensureInit() {
        if (initialized) {
            return;
        }
        resetPreCookies();
        try {
            String username = getAnonimousUsername();
            JSONObject root = registerAnonimous(
                    new JSONObject().put("username", username).put("e_r", true));
            if (!"200".equals(String.valueOf(root.opt("code")))) {
                throw new IOException("NetEase anonymous login failed: " + root);
            }
            if (!cookieJar.containsKey("WNMCID")) {
                cookieJar.put("WNMCID", randomChars(6, "abcdefghijklmnopqrstuvwxyz") + "." + System.currentTimeMillis() + ".01.0");
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
            Logger.printDebug(() -> "Ensure NetEase init failed", ex);
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
            return new JSONObject();
        }

        captureCookies(connection);

        String decrypted = decryptEapiResponse(connection);
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

    private static JSONObject registerAnonimous(JSONObject params) throws IOException, JSONException {
        String body = "params=" + buildEapiParams(REGISTER_PATH, params);
        Map<String, String> headers = new HashMap<>();
        headers.put("Referer", "https://music.163.com/");
        headers.put("User-Agent", NETEASE_USER_AGENT);
        headers.put("Accept", "*/*");
        headers.put("Host", "interface.music.163.com");
        String cookie = cookieHeader();
        if (!cookie.isEmpty()) {
            headers.put("Cookie", cookie);
        }
        HttpURLConnection connection = LyricsRequests.postForm(EAPI_HOST + REGISTER_PATH, body, headers);
        int httpCode = connection.getResponseCode();
        if (httpCode != 200) {
            LyricsRequests.logFailure("NetEase", connection);
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
        return LyricsCrypto.aesEcbPkcs5DecryptBase64ToString(base64, EAPI_KEY);
    }

    private static String getAnonimousUsername() {
        StringBuilder xored = new StringBuilder();
        for (int i = 0; i < deviceId.length(); i++) {
            char left = deviceId.charAt(i);
            char right = DEVICEID_XOR_KEY.charAt(i % DEVICEID_XOR_KEY.length());
            xored.append((char) (left ^ right));
        }
        byte[] md5 = LyricsCrypto.md5Bytes(xored.toString().getBytes(StandardCharsets.UTF_8));
        String base64Md5 = Base64.encodeToString(md5, Base64.NO_WRAP);
        String combined = deviceId + " " + base64Md5;
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
            //noinspection SizeReplaceableByIsEmpty
            if (builder.length() > 0) {
                builder.append("; ");
            }
            builder.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return builder.toString();
    }

    private static List<LyricsLine> parseNeteaseOriginalLyrics(String yrc, String lrc) {
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

    private static List<LyricsLine> parseYrc(String text) {
        List<LyricsLine> lines = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return lines;
        }

        for (String rawLine : text.split("\\r?\\n")) {
            String line = rawLine.trim();
            if (line.startsWith("{")) {
                continue;
            }

            Matcher lineMatch = YRC_LINE.matcher(line);
            if (!lineMatch.matches()) {
                continue;
            }

            long lineStart = Long.parseLong(Objects.requireNonNull(lineMatch.group(1)));
            long lineDuration = Long.parseLong(Objects.requireNonNull(lineMatch.group(2)));
            long lineEnd = lineStart + lineDuration;
            String content = Objects.requireNonNull(lineMatch.group(3));

            List<Long> starts = new ArrayList<>();
            List<Long> durations = new ArrayList<>();
            List<String> texts = new ArrayList<>();
            Matcher wordMatch = YRC_WORD.matcher(content);
            while (wordMatch.find()) {
                starts.add(Long.parseLong(Objects.requireNonNull(wordMatch.group(1))));
                durations.add(Long.parseLong(Objects.requireNonNull(wordMatch.group(2))));
                texts.add(wordMatch.group(3));
            }

            List<Word> words = new ArrayList<>();
            StringBuilder full = new StringBuilder();
            for (int i = 0; i < starts.size(); i++) {
                long wordStart = starts.get(i);
                long wordEnd = wordStart + durations.get(i);
                String wordText = texts.get(i);
                if (wordText.isEmpty()) {
                    continue;
                }
                boolean endsWithSpace =
                        Character.isWhitespace(wordText.charAt(wordText.length() - 1));
                String trimmed = wordText.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                //noinspection SizeReplaceableByIsEmpty
                if (full.length() > 0 && needsSpaceBetween(full.toString(), trimmed)) {
                    full.append(' ');
                }
                words.add(new Word(wordStart, wordEnd, trimmed, null, endsWithSpace));
                full.append(trimmed);
            }
            if (words.isEmpty() && !content.isEmpty()) {
                full.append(content);
            }

            String fullText = full.toString().trim();
            if (fullText.isEmpty()) {
                continue;
            }
            lines.add(new LyricsLine(lineStart, lineEnd, fullText, words));
        }

        lines.sort(Comparator.comparingLong(LyricsLine::startTimeMs));
        return lines;
    }

    private static List<LyricsLine> parseLrc(String text) {
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

        items.sort(Comparator.comparingLong(Item::start));
        for (Item item : items) {
            lines.add(new LyricsLine(item.start(), item.text()));
        }
        return lines;
    }

    private static List<LyricsLine> parseMixedNeteaseLyrics(String text) {
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
                } catch (Exception ex) {
                    Logger.printDebug(() -> "Could not parse NetEase LRC item", ex);
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

        items.sort(Comparator.comparingLong(Item::start));
        List<LyricsLine> lines = new ArrayList<>();
        for (Item item : items) {
            lines.add(new LyricsLine(item.start(), item.text()));
        }
        return lines;
    }

    private static long parseTimeMs(String minutes, String seconds, String fraction) {
        long min = Long.parseLong(minutes);
        long sec = Long.parseLong(seconds);
        StringBuilder fractionText = new StringBuilder(fraction == null ? "0" : fraction);
        while (fractionText.length() < 3) {
            fractionText.append('0');
        }
        return (min * 60 + sec) * 1000 + Long.parseLong(fractionText.substring(0, 3));
    }

    private record Item(long start, String text) {}

    private static String randomChars(int length, String alphabet) {
        StringBuilder builder = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            builder.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
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
        return randomMac() + "@@@" + randomChars(8, "ABCDEFGHIJKLMNOPQRSTUVWXYZ") + "@@@@@@" + randomChars(64, "0123456789abcdef");
    }

    private static boolean isCjk(char c) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
                || block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS
                || block == Character.UnicodeBlock.HIRAGANA
                || block == Character.UnicodeBlock.KATAKANA
                || block == Character.UnicodeBlock.HANGUL_JAMO
                || block == Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO
                || block == Character.UnicodeBlock.HANGUL_SYLLABLES;
    }

    private static boolean needsSpaceBetween(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) {
            return false;
        }
        char last = a.charAt(a.length() - 1);
        char first = b.charAt(0);
        return !(isCjk(last) || isCjk(first));
    }
}
