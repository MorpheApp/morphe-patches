/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.requests;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.InflaterInputStream;

import app.morphe.extension.music.patches.lyrics.model.Lyrics;
import app.morphe.extension.music.patches.lyrics.model.LyricsLine;
import app.morphe.extension.music.patches.lyrics.model.TrackInfo;
import app.morphe.extension.music.patches.lyrics.model.Word;
import app.morphe.extension.music.patches.lyrics.parsers.LRCParser;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.requests.Requester;

public final class KuwoProvider implements LyricsProvider {

    private static final String SEARCH_URL = "https://search.kuwo.cn/r.s";
    private static final String LYRIC_URL = "https://newlyric.kuwo.cn/newlyric.lrc";
    private static final String MOBILE_LYRIC_URL = "https://m.kuwo.cn/newh5/singles/songinfoandlrc";

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private static final byte[] XOR_KEY = {'y', 'e', 'e', 'l', 'i', 'o', 'n'};

    private static final Charset GB18030 = Charset.forName("GB18030");

    private static final Pattern TIME_LINE = Pattern.compile("^\\[(\\d{2}):(\\d{2})\\.(\\d{2,3})\\](.*)$");
    private static final Pattern TAG_LINE = Pattern.compile("^\\[([A-Za-z]+):([^\\]]*)\\]$");
    private static final Pattern WORD = Pattern.compile("<(-?\\d+),(-?\\d+)>([^<]*)");
    private static final Pattern SCALE = Pattern.compile("\\[kuwo:(\\d+)\\]");
    private static final Pattern CHINESE = Pattern.compile("[\\u4E00-\\u9FA5]");

    @Override
    public String name() {
        return "Kuwo";
    }

    @Override
    public boolean hasCandidates() {
        return true;
    }

    @Nullable
    @Override
    public FetchResult fetch(TrackInfo track) throws Exception {
        if (track.title().isEmpty() || track.artist().isEmpty()) {
            return null;
        }
        List<ScoredCandidate> candidates = search(track);
        if (candidates.isEmpty()) {
            return null;
        }
        ScoredCandidate best = candidates.get(0);
        Lyrics lyrics = fetchLyrics(best.musicId, track, best.title, best.artist, best.album);
        if (lyrics != null && !lyrics.isEmpty()) {
            return FetchResult.of(lyrics, best.title, best.artist, best.duration, track);
        }
        return null;
    }

    @Override
    public List<Lyrics.ScoredLyrics> fetchCandidates(TrackInfo track) throws Exception {
        List<Lyrics.ScoredLyrics> scored = new ArrayList<>();
        if (track.title().isEmpty() || track.artist().isEmpty()) {
            return scored;
        }
        List<ScoredCandidate> candidates = search(track);
        for (ScoredCandidate c : candidates) {
            Lyrics lyrics = fetchLyrics(c.musicId, track, c.title, c.artist, c.album);
            if (lyrics != null && !lyrics.isEmpty()) {
                int score = LyricsRequests.scoreLyricsCandidate(c.title, c.artist, c.duration, lyrics, track);
                scored.add(new Lyrics.ScoredLyrics(score, lyrics));
            }
        }
        List<Lyrics.ScoredLyrics> ordered = new ArrayList<>(scored);
        ordered.sort((a, b) -> b.score() - a.score());
        ordered.sort((a, b) -> LyricsRequests.syncRank(b.lyrics()) - LyricsRequests.syncRank(a.lyrics()));
        return ordered.size() > LyricsRequests.MAX_CANDIDATES
                ? new ArrayList<>(ordered.subList(0, LyricsRequests.MAX_CANDIDATES))
                : ordered;
    }

    private static List<ScoredCandidate> search(TrackInfo track) throws Exception {
        String keyword = track.artist() + " " + track.title();
        String url = SEARCH_URL + "?all=" + LyricsRequests.encode(keyword)
                + "&ft=music&rformat=json&encoding=utf8&pcjson=1&rn=20&pn=0";

        HttpURLConnection connection = LyricsRequests.openConnection(url);
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.setRequestProperty("Accept", "application/json, text/plain, */*");
        connection.setRequestProperty("Referer", "https://www.kuwo.cn/");
        if (connection.getResponseCode() != Requester.HTTP_STATUS_CODE_SUCCESS) {
            LyricsRequests.logFailure("Kuwo", connection);
            return List.of();
        }

        JSONObject root = Requester.parseJSONObject(connection);
        JSONArray abslist = root == null ? null : root.optJSONArray("abslist");
        if (abslist == null || abslist.length() == 0) {
            return List.of();
        }

        List<ScoredCandidate> result = new ArrayList<>();
        for (int i = 0; i < abslist.length(); i++) {
            JSONObject item = abslist.optJSONObject(i);
            if (item == null) continue;
            String musicId = item.optString("DC_TARGETID", "");
            if (musicId.isEmpty()) musicId = item.optString("MUSICRID", "");
            musicId = musicId.replaceAll("[^0-9]", "");
            if (musicId.isEmpty()) continue;

            String title = item.optString("SONGNAME", "");
            if (title.isEmpty()) title = item.optString("NAME", "");
            String artist = item.optString("ARTIST", "");
            String album = item.optString("ALBUM", "");
            int duration = item.optInt("DURATION", 0);

            int score = LyricsRequests.scoreTrackCandidate(title, artist, duration, track);
            if (score < LyricsRequests.SOFT_MIN) continue;

            boolean dup = false;
            for (ScoredCandidate c : result) {
                if (c.musicId.equals(musicId)) {
                    dup = true;
                    break;
                }
            }
            if (dup) continue;

            result.add(new ScoredCandidate(musicId, title, artist, album, duration, score));
        }

        result.sort((a, b) -> b.score - a.score);
        if (result.size() > LyricsRequests.MAX_CANDIDATES) {
            result = new ArrayList<>(result.subList(0, LyricsRequests.MAX_CANDIDATES));
        }
        return result;
    }

    @Nullable
    private Lyrics fetchLyrics(String musicId, TrackInfo track, String title, String artist, String album) {
        String sourceUrl = "https://www.kuwo.cn/play_detail/" + musicId;
        try {
            String encrypted = fetchEncryptedLyrics(musicId);
            if (encrypted != null && !encrypted.isEmpty()) {
                Lyrics lyrics = parseKuwoLrc(encrypted, title, artist, album, sourceUrl);
                if (lyrics != null && !lyrics.isEmpty()) {
                    return lyrics;
                }
            }
        } catch (Exception ex) {
            Logger.printDebug(() -> "Kuwo encrypted lyrics failed for " + musicId, ex);
        }
        try {
            JSONArray lrclist = fetchMobileLyrics(musicId);
            if (lrclist != null && lrclist.length() > 0) {
                Lyrics lyrics = parseMobileLrc(lrclist, title, artist, album, sourceUrl);
                if (lyrics != null && !lyrics.isEmpty()) {
                    return lyrics;
                }
            }
        } catch (Exception ex) {
            Logger.printDebug(() -> "Kuwo mobile lyrics failed for " + musicId, ex);
        }
        return null;
    }

    @Nullable
    private static String fetchEncryptedLyrics(String musicId) throws IOException {
        String params = "user=12345,web,web,web&requester=localhost&req=1&rid=MUSIC_"
                + musicId + "&lrcx=1";
        String encoded = buildLyricParams(params);

        String url = LYRIC_URL + "?" + encoded;
        HttpURLConnection connection = LyricsRequests.openConnection(url);
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.setRequestProperty("Accept", "*/*");
        connection.setRequestProperty("Referer", "https://www.kuwo.cn/");
        if (connection.getResponseCode() != Requester.HTTP_STATUS_CODE_SUCCESS) {
            LyricsRequests.logFailure("Kuwo", connection);
            return null;
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = connection.getInputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
        }
        byte[] raw = out.toByteArray();
        return decryptLyrics(raw);
    }

    @Nullable
    private static JSONArray fetchMobileLyrics(String musicId) throws IOException, JSONException {
        String url = MOBILE_LYRIC_URL + "?musicId=" + LyricsRequests.encode(musicId);
        HttpURLConnection connection = LyricsRequests.openConnection(url);
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Referer", "https://www.kuwo.cn/");
        if (connection.getResponseCode() != Requester.HTTP_STATUS_CODE_SUCCESS) {
            LyricsRequests.logFailure("Kuwo", connection);
            return null;
        }
        JSONObject root = Requester.parseJSONObject(connection);
        if (root == null) return null;
        JSONObject data = root.optJSONObject("data");
        if (data == null) return null;
        return data.optJSONArray("lrclist");
    }

    /** XORs the params with the "yeelion" key, then Base64-encodes them. */
    public static String buildLyricParams(String params) {
        byte[] bytes = params.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] xored = new byte[bytes.length];
        for (int i = 0; i < bytes.length; i++) {
            xored[i] = (byte) (bytes[i] ^ XOR_KEY[i % XOR_KEY.length]);
        }
        return Base64.getEncoder().encodeToString(xored);
    }

    @Nullable
    public static String decryptLyrics(byte[] raw) {
        if (raw == null || raw.length == 0) return null;
        StringBuilder head = new StringBuilder();
        for (int i = 0; i < Math.min(10, raw.length); i++) {
            head.append((char) (raw[i] & 0xFF));
        }
        if (!head.toString().toLowerCase(Locale.ROOT).equals("tp=content")) {
            return null;
        }
        // find \r\n\r\n
        int headerEnd = -1;
        for (int j = 0; j < raw.length - 3; j++) {
            if (raw[j] == 13 && raw[j + 1] == 10 && raw[j + 2] == 13 && raw[j + 3] == 10) {
                headerEnd = j + 4;
                break;
            }
        }
        if (headerEnd < 0) return null;

        String inflated;
        try (InputStream in = new InflaterInputStream(new ByteArrayInputStream(raw, headerEnd, raw.length - headerEnd))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            inflated = out.toString(java.nio.charset.StandardCharsets.UTF_8.name());
        } catch (Exception ex) {
            Logger.printDebug(() -> "Kuwo inflate failed", ex);
            return null;
        }
        if (inflated == null || inflated.isEmpty()) return null;

        byte[] payload;
        try {
            payload = Base64.getDecoder().decode(inflated.trim());
        } catch (Exception ex) {
            Logger.printDebug(() -> "Kuwo base64 decode failed", ex);
            return null;
        }
        if (payload == null || payload.length == 0) return null;

        byte[] plain = new byte[payload.length];
        for (int k = 0; k < payload.length; k++) {
            plain[k] = (byte) (payload[k] ^ XOR_KEY[k % XOR_KEY.length]);
        }
        return new String(plain, GB18030);
    }

    /** Parses the octal [kuwo:xxx] scale tag, defaulting to {1, 1}. */
    public static int[] parseScale(String text) {
        Matcher m = SCALE.matcher(text == null ? "" : text);
        if (!m.find()) return new int[]{1, 1};
        int value;
        try {
            value = Integer.parseInt(m.group(1), 8);
        } catch (NumberFormatException ex) {
            return new int[]{1, 1};
        }
        int first = value / 10;
        int second = value % 10;
        if (first == 0 || second == 0) return new int[]{1, 1};
        return new int[]{first, second};
    }

    private static final class RawLine {
        final long start;
        String text;
        final List<Word> words;

        RawLine(long start, String text, List<Word> words) {
            this.start = start;
            this.text = text;
            this.words = words;
        }
    }

    public static Lyrics parseKuwoLrc(String text, String title, String artist, String album) {
        return parseKuwoLrc(text, title, artist, album, null);
    }

    public static Lyrics parseKuwoLrc(String text, String title, String artist, String album, @Nullable String sourceUrl) {
        if (text == null || text.isEmpty()) return null;

        int[] scale = parseScale(text);

        Map<String, String> tags = new java.util.HashMap<>();
        List<RawLine> rawLines = new ArrayList<>();
        List<LyricsLine> translationLines = new ArrayList<>();

        long fileOffsetMs = 0;
        for (String rawLine : text.split("\\r?\\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) continue;
            Matcher tagMatch = TAG_LINE.matcher(line);
            if (tagMatch.matches()) {
                String key = tagMatch.group(1);
                String value = tagMatch.group(2);
                if (key != null && value != null) {
                    if (key.equalsIgnoreCase("offset")) {
                        try {
                            fileOffsetMs = -Long.parseLong(value.trim());
                        } catch (NumberFormatException ex) {
                            // Invalid offset: keep 0.
                        }
                    } else {
                        tags.put(key.toLowerCase(Locale.ROOT), value);
                    }
                }
                continue;
            }
        }

        for (String rawLine : text.split("\\r?\\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) continue;

            Matcher tagMatch = TAG_LINE.matcher(line);
            if (tagMatch.matches()) {
                continue; // already handled above
            }

            Matcher timeMatch = TIME_LINE.matcher(line);
            if (!timeMatch.matches()) continue;

            String mm = timeMatch.group(1);
            String ss = timeMatch.group(2);
            String frac = timeMatch.group(3);
            String content = timeMatch.group(4) == null ? "" : timeMatch.group(4);

            int millis = frac.length() == 2 ? Integer.parseInt(frac) * 10 : Integer.parseInt(frac);
            long start = Long.parseLong(mm) * 60000L + Long.parseLong(ss) * 1000L + millis;
            String plain = stripTags(content).trim();
            if (plain.isEmpty()) continue;

            // translation line: starts with <0,0> and contains Chinese
            if (content.trim().startsWith("<0,0>") && CHINESE.matcher(content).find()) {
                translationLines.add(new LyricsLine(start, start, plain, List.of(), null, false, false, null));
                continue;
            }

            List<Word> words = new ArrayList<>();
            Matcher wm = WORD.matcher(content);
            while (wm.find()) {
                int p1;
                int p2;
                try {
                    p1 = Integer.parseInt(wm.group(1));
                    p2 = Integer.parseInt(wm.group(2));
                } catch (NumberFormatException ex) {
                    continue;
                }
                String wordText = wm.group(3);
                if (wordText == null || wordText.isEmpty()) continue;
                long wordStart = start + Math.abs((p1 + p2) / (scale[0] * 2));
                long wordEnd = wordStart + Math.abs((p1 - p2) / (scale[1] * 2));
                if (wordEnd <= wordStart) wordEnd = wordStart + 80;
                words.add(new Word(wordStart, wordEnd, wordText, null, false));
            }

            rawLines.add(new RawLine(start, plain, words));
        }

        rawLines.sort((a, b) -> Long.compare(a.start, b.start));

        List<RawLine> deduped = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (RawLine r : rawLines) {
            String key = r.start + "|" + r.text;
            if (seen.contains(key)) continue;
            seen.add(key);
            deduped.add(r);
        }

        List<LyricsLine> lines = new ArrayList<>();
        for (int i = 0; i < deduped.size(); i++) {
            RawLine cur = deduped.get(i);
            long end;
            if (!cur.words.isEmpty()) {
                end = cur.words.get(cur.words.size() - 1).endMs();
            } else {
                long nextStart = (i + 1 < deduped.size()) ? deduped.get(i + 1).start : cur.start + 4000;
                end = (nextStart > cur.start + 200) ? nextStart - 10 : cur.start + 2000;
            }
            if (end <= cur.start) end = cur.start + 200;
            List<Word> ws = cur.words.isEmpty() ? List.of() : cur.words;
            long s = Math.max(0, cur.start + fileOffsetMs);
            long e = Math.max(0, end + fileOffsetMs);
            List<Word> shiftedWords = new ArrayList<>();
            for (Word w : ws) {
                shiftedWords.add(new Word(Math.max(0, w.startMs() + fileOffsetMs),
                        Math.max(0, w.endMs() + fileOffsetMs), w.text(), w.romaji(), w.endsWithSpace(), w.ruby()));
            }
            lines.add(new LyricsLine(s, e, cur.text, shiftedWords, null, false, false, null));
        }

        // Match a translation to the nearest preceding original line.
        List<LyricsLine> translated = new ArrayList<>();
        for (LyricsLine t : translationLines) {
            long tStart = Math.max(0, t.startTimeMs() + fileOffsetMs);
            LyricsLine target = null;
            for (LyricsLine l : lines) {
                if (l.startTimeMs() <= tStart + 30) target = l;
                else break;
            }
            if (target == null) continue;
            if (!translated.isEmpty() && translated.get(translated.size() - 1).startTimeMs() == target.startTimeMs()) {
                LyricsLine prev = translated.get(translated.size() - 1);
                translated.set(translated.size() - 1, new LyricsLine(prev.startTimeMs(), prev.endTimeMs(), t.text(), List.of(), null, false, false, null));
                continue;
            }
            translated.add(new LyricsLine(target.startTimeMs(), target.endTimeMs(), t.text(), List.of(), null, false, false, null));
        }

        // Caller metadata takes priority over the file tags.
        if (title != null && !title.isEmpty()) tags.put("ti", title);
        if (artist != null && !artist.isEmpty()) tags.put("ar", artist);
        if (album != null && !album.isEmpty()) tags.put("al", album);

        List<String> songwriters = new ArrayList<>();
        if (tags.get("by") != null && !tags.get("by").isEmpty()) songwriters.add(tags.get("by"));
        if (tags.get("lyricist") != null && !tags.get("lyricist").isEmpty())
            songwriters.add(tags.get("lyricist"));

        List<String> creditLines = LRCParser.extractCreditMetadata(text);

        boolean synced = !lines.isEmpty() && lines.get(0).startTimeMs() != LyricsLine.NO_TIME;
        boolean hasWordSync = false;
        for (LyricsLine l : lines) {
            if (!l.words().isEmpty()) {
                hasWordSync = true;
                break;
            }
        }

        Map<String, List<LyricsLine>> transMap = null;
        if (!translated.isEmpty()) {
            transMap = new java.util.HashMap<>();
            transMap.put("default", translated);
        }

        return new Lyrics(lines, "Kuwo", synced,
                null, transMap, null,
                songwriters.isEmpty() ? null : songwriters,
                creditLines.isEmpty() ? null : creditLines,
                text,
                "kw",
                sourceUrl,
                null, null);
    }

    public static Lyrics parseMobileLrc(JSONArray lrclist, String title, String artist, String album) {
        return parseMobileLrc(lrclist, title, artist, album, null);
    }

    public static Lyrics parseMobileLrc(JSONArray lrclist, String title, String artist, String album, @Nullable String sourceUrl) {
        if (lrclist == null || lrclist.length() == 0) return null;
        List<String> texts = new ArrayList<>();
        List<Long> times = new ArrayList<>();
        for (int i = 0; i < lrclist.length(); i++) {
            JSONObject item = lrclist.optJSONObject(i);
            if (item == null) continue;
            String timeStr = item.optString("time", "");
            String text = stripTags(item.optString("lineLyric", item.optString("lyric", ""))).trim();
            if (text.isEmpty()) continue;
            double seconds;
            try {
                seconds = Double.parseDouble(timeStr);
            } catch (NumberFormatException ex) {
                continue;
            }
            if (!Double.isFinite(seconds)) continue;
            times.add(Math.round(seconds * 1000));
            texts.add(text);
        }
        if (times.isEmpty()) return null;

        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < times.size(); i++) order.add(i);
        order.sort((a, b) -> Long.compare(times.get(a), times.get(b)));

        List<LyricsLine> lines = new ArrayList<>();
        for (int j = 0; j < order.size(); j++) {
            int idx = order.get(j);
            long start = times.get(idx);
            long end = (j + 1 < order.size()) ? times.get(order.get(j + 1)) : start + 4000;
            if (end <= start) end = start + 500;
            lines.add(new LyricsLine(start, end, texts.get(idx), List.of(), null, false, false, null));
        }

        Map<String, String> tags = new java.util.HashMap<>();
        tags.put("ti", title == null ? "" : title);
        tags.put("ar", artist == null ? "" : artist);
        tags.put("al", album == null ? "" : album);

        List<String> songwriters = new ArrayList<>();
        if (tags.get("by") != null && !tags.get("by").isEmpty()) songwriters.add(tags.get("by"));

        return new Lyrics(lines, "Kuwo", true, null, null, null, null,
                songwriters.isEmpty() ? null : songwriters,
                null, "lrc", sourceUrl, null, null);
    }

    private static String stripTags(String text) {
        return text == null ? "" : text.replaceAll("<[^>]*>", "");
    }

    private record ScoredCandidate(String musicId, String title, String artist, String album,
                                   int duration, int score) {
    }
}