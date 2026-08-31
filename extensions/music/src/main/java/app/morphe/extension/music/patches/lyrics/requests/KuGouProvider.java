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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.InflaterInputStream;

import app.morphe.extension.music.patches.lyrics.LrcParser;
import app.morphe.extension.music.patches.lyrics.Lyrics;
import app.morphe.extension.music.patches.lyrics.LyricsLine;
import app.morphe.extension.music.patches.lyrics.TrackInfo;
import app.morphe.extension.music.patches.lyrics.Word;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.requests.Requester;

/**
 * KuGou lyrics, used as a fallback because it covers many tracks LRCLIB does not.
 */
public final class KuGouProvider implements LyricsProvider {

    private static final String SONG_SEARCH_URL =
            "https://mobiles.kugou.com/api/v3/search/song?version=10000&plat=0&correct=1&pagesize=10";

    private static final String SEARCH_URL = "https://lyrics.kugou.com/search?ver=1&man=yes&client=mobi&hash=";
    private static final String DOWNLOAD_URL = "https://lyrics.kugou.com/download?ver=1&client=pc&fmt=krc&charset=utf8";

    private static final byte[] KRC_KEY = {
            64, 71, 97, 119, 94, 50, 116, 71, 81, 54, 49, 45, (byte) 206, (byte) 210, 110, 105
    };

    private static final Pattern KRC_META = Pattern.compile("^\\[(\\w+):([^\\]]*)]$");
    private static final Pattern KRC_LINE = Pattern.compile("^\\[(\\d+),(\\d+)](.*)");
    private static final Pattern KRC_WORD = Pattern.compile("<(\\d+),(\\d+),(\\d+)>([^<]*)");

    /**
     * KuGou lyrics frequently start with credit lines that are not part of the song.
     */
    private static final String[] CREDIT_LINE_MARKERS = {
            "作词", "作曲", "编曲", "制作人", "混音", "母带", "录音", "吉他", "贝斯", "鼓",
            "和声", "监制", "出品", "发行", "词：", "曲：", "唱：",
    };

    @Override
    public String name() {
        return "KuGou";
    }

    @Nullable
    @Override
    public Lyrics fetch(TrackInfo track) throws Exception {
        String hash = resolveHash(track);
        if (hash == null || hash.isEmpty()) {
            Logger.printDebug(() -> "KuGou could not resolve a file hash for " + track);
            return null;
        }

        String searchUrl = SEARCH_URL + encode(hash);
        HttpURLConnection searchConnection = LyricsRequests.openConnection(searchUrl);
        if (searchConnection.getResponseCode() != 200) {
            LyricsRequests.logFailure(name(), searchConnection);
            return null;
        }

        JSONObject searchResponse = Requester.parseJSONObject(searchConnection);
        JSONArray candidates = searchResponse.optJSONArray("candidates");
        if (candidates == null || candidates.length() == 0) {
            return null;
        }

        JSONObject candidate = candidates.optJSONObject(0);
        if (candidate == null) {
            return null;
        }

        String id = candidate.optString("id", "");
        String accessKey = candidate.optString("accesskey", "");
        if (id.isEmpty() || accessKey.isEmpty()) {
            return null;
        }

        String downloadUrl = DOWNLOAD_URL + "&id=" + encode(id) + "&accesskey=" + encode(accessKey);
        HttpURLConnection downloadConnection = LyricsRequests.openConnection(downloadUrl);
        if (downloadConnection.getResponseCode() != 200) {
            LyricsRequests.logFailure(name(), downloadConnection);
            return null;
        }

        JSONObject downloadResponse = Requester.parseJSONObject(downloadConnection);
        String content = downloadResponse.optString("content", "");
        if (content.isEmpty()) {
            return null;
        }

        byte[] raw = Base64.decode(content, Base64.DEFAULT);
        List<LyricsLine> lines;
        if (raw.length > 4 && raw[0] == 'k' && raw[1] == 'r' && raw[2] == 'c' && raw[3] == '1') {
            lines = removeCreditLines(parseKrc(decryptKrc(raw)));
        } else {
            // Some tracks only expose plain LRC even when KRC is requested.
            String lrc = new String(raw, StandardCharsets.UTF_8);
            lines = removeCreditLines(LrcParser.parseSynced(lrc));
        }
        if (lines.isEmpty()) {
            return null;
        }

        Logger.printDebug(() -> "KuGou returned " + lines.size()
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

    @Nullable
    private static String resolveHash(TrackInfo track) throws IOException, JSONException {
        String keyword = track.artist() + " " + track.title();
        String url = SONG_SEARCH_URL + "&keyword=" + encode(keyword);
        HttpURLConnection connection = LyricsRequests.openConnection(url);
        if (connection.getResponseCode() != 200) {
            LyricsRequests.logFailure("KuGou", connection);
            return null;
        }

        JSONObject root = Requester.parseJSONObject(connection);
        JSONObject data = root.optJSONObject("data");
        JSONArray info = data == null ? null : data.optJSONArray("info");
        if (info == null || info.length() == 0) {
            return null;
        }

        String wantedTitle = track.title().toLowerCase(Locale.ROOT);
        String wantedArtist = track.artist().toLowerCase(Locale.ROOT);
        String bestHash = null;
        int bestScore = -1;
        for (int i = 0; i < info.length(); i++) {
            JSONObject item = info.optJSONObject(i);
            if (item == null) {
                continue;
            }
            String hash = item.optString("hash", "");
            if (hash.isEmpty()) {
                continue;
            }

            String title = item.optString("songname", "").toLowerCase(Locale.ROOT);
            String artist = item.optString("singername", "").toLowerCase(Locale.ROOT);
            int score = 0;
            if (!title.isEmpty() && (title.contains(wantedTitle) || wantedTitle.contains(title))) {
                score += 2;
            }
            if (!artist.isEmpty() && artist.contains(wantedArtist)) {
                score += 2;
            }
            if (track.durationSeconds() > 0) {
                int duration = item.optInt("duration", 0);
                if (duration > 0 && Math.abs(duration - track.durationSeconds()) <= 5) {
                    score += 2;
                }
            }
            if (score > bestScore) {
                bestScore = score;
                bestHash = hash;
            }
        }
        return bestHash;
    }

    private static String decryptKrc(byte[] raw) throws IOException {
        byte[] body = Arrays.copyOfRange(raw, 4, raw.length);
        byte[] decoded = new byte[body.length];
        for (int i = 0; i < body.length; i++) {
            decoded[i] = (byte) (body[i] ^ KRC_KEY[i % KRC_KEY.length]);
        }

        InputStream input = new InflaterInputStream(new ByteArrayInputStream(decoded));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = input.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        input.close();
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static List<LyricsLine> parseKrc(String krc) {
        List<LyricsLine> lines = new ArrayList<>();
        if (krc == null || krc.isEmpty()) {
            return lines;
        }

        long fileOffsetMs = 0;
        for (String rawLine : krc.split("\\r?\\n")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.charAt(0) != '[') {
                continue;
            }

            Matcher meta = KRC_META.matcher(line);
            if (meta.matches()) {
                String name = meta.group(1).toLowerCase(Locale.ROOT);
                if (name.equals("offset")) {
                    try {
                        fileOffsetMs = -Long.parseLong(meta.group(2).trim());
                    } catch (NumberFormatException ignored) {
                    }
                }
                continue;
            }

            Matcher lineMatch = KRC_LINE.matcher(line);
            if (!lineMatch.matches()) {
                continue;
            }

            long lineStart = Math.max(0, Long.parseLong(lineMatch.group(1)) + fileOffsetMs);
            long lineDuration = Long.parseLong(lineMatch.group(2));
            long lineEnd = lineStart + lineDuration;
            String content = lineMatch.group(3);

            List<Long> offsets = new ArrayList<>();
            List<String> texts = new ArrayList<>();
            Matcher wordMatch = KRC_WORD.matcher(content);
            while (wordMatch.find()) {
                offsets.add(Long.parseLong(wordMatch.group(1)));
                texts.add(wordMatch.group(4));
            }

            List<Word> words = new ArrayList<>();
            StringBuilder full = new StringBuilder();
            for (int i = 0; i < offsets.size(); i++) {
                long wordStart = lineStart + offsets.get(i);
                long wordEnd = (i < offsets.size() - 1) ? lineStart + offsets.get(i + 1) : lineEnd;
                String text = texts.get(i);
                words.add(new Word(wordStart, wordEnd, text));
                full.append(text);
            }
            if (words.isEmpty() && !content.isEmpty()) {
                words.add(new Word(lineStart, lineEnd, content));
                full.append(content);
            }

            String text = full.toString().trim();
            if (text.isEmpty()) {
                continue;
            }
            lines.add(new LyricsLine(lineStart, text, words));
        }

        lines.sort(Comparator.comparingLong(LyricsLine::startTimeMs));
        return lines;
    }

    /**
     * Drops the leading credit lines. Only leading lines are checked so that a
     * lyric that happens to contain one of the markers is kept.
     */
    private static List<LyricsLine> removeCreditLines(List<LyricsLine> lines) {
        int firstLyric = 0;
        while (firstLyric < lines.size() && isCreditLine(lines.get(firstLyric).text())) {
            firstLyric++;
        }

        if (firstLyric == 0) {
            return lines;
        }
        // Every line being a credit line means the parse produced nothing usable.
        if (firstLyric == lines.size()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(lines.subList(firstLyric, lines.size()));
    }

    private static boolean isCreditLine(String text) {
        if (text.isEmpty()) {
            return true;
        }
        for (String marker : CREDIT_LINE_MARKERS) {
            if (text.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The Charset overload of encode() needs API 33, so the charset is named instead.
     */
    @SuppressWarnings("CharsetObjectCanBeUsed")
    private static String encode(String value) throws UnsupportedEncodingException {
        return URLEncoder.encode(value, "UTF-8");
    }
}
