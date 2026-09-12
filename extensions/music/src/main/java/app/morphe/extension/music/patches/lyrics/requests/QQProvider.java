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

import java.io.IOException;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.music.patches.lyrics.LrcParser;
import app.morphe.extension.music.patches.lyrics.Lyrics;
import app.morphe.extension.music.patches.lyrics.LyricsLine;
import app.morphe.extension.music.patches.lyrics.LyricsMerge;
import app.morphe.extension.music.patches.lyrics.TrackInfo;
import app.morphe.extension.music.patches.lyrics.Word;
import app.morphe.extension.shared.requests.Requester;

/**
 * QQ Music lyrics. Prefers the word-synced QRC format, then falls back to plain
 * line-synced LRC. Translations and romanizations are intentionally ignored.
 */
public final class QQProvider implements LyricsProvider {

    private static final String MUSICU_URL = "https://u.y.qq.com/cgi-bin/musicu.fcg";
    private static final String QRC_KEY = "!@#)(*$%123ZXC!@!@#)(NHL";

    private static JSONObject musicuComm() throws JSONException {
        JSONObject comm = new JSONObject();
        comm.put("ct", "11");
        comm.put("cv", "1003006");
        comm.put("v", "1003006");
        comm.put("os_ver", "15");
        comm.put("phonetype", "24122RKC7C");
        comm.put("tmeAppID", "qqmusiclight");
        comm.put("nettype", "NETWORK_WIFI");
        return comm;
    }

    private static final Pattern QRC_XML = Pattern.compile(
            "<Lyric_1 LyricType=\"1\" LyricContent=\"([\\s\\S]*?)\"/>");
    private static final Pattern NUMERIC_ENTITY = Pattern.compile("&#(\\d+);");
    private static final Pattern QRC_META = Pattern.compile("^\\[(\\w+):([^\\]]*)]$");
    private static final Pattern QRC_LINE = Pattern.compile("^\\[(\\d+),(\\d+)](.*)");
    private static final Pattern QRC_WORD = Pattern.compile("\\((\\d+),(\\d+)\\)");
    private static final Pattern QRC_WHOLE_LINE_COMMENT = Pattern.compile("//");

    private static final java.util.Set<String> LRC_CREDIT_META_KEYS =
            java.util.Set.of("ti", "ar", "al", "au");

    @Override
    public String name() {
        return "QQ";
    }

    @Override
    public boolean hasCandidates() {
        return true;
    }

    @Nullable
    @Override
    public Lyrics fetch(TrackInfo track) throws Exception {
        String keyword = track.title() + " " + track.artist();
        JSONObject song = searchBest(keyword, track);
        if (song == null || !song.has("id")) {
            return null;
        }
        return fetchFromSong(song, track);
    }

    @Override
    public List<Lyrics> fetchCandidates(TrackInfo track) throws Exception {
        String keyword = track.title() + " " + track.artist();
        List<JSONObject> candidates = searchAll(keyword, track);
        List<Lyrics> results = new ArrayList<>();
        for (JSONObject song : candidates) {
            if (results.size() >= 5) {
                break;
            }
            if (song == null || !song.has("id")) {
                continue;
            }
            try {
                Lyrics lyrics = fetchFromSong(song, track);
                if (lyrics != null) {
                    results.add(lyrics);
                }
            } catch (Exception ex) {
            }
        }
        return results;
    }

    @Nullable
    private Lyrics fetchFromSong(JSONObject song, TrackInfo track) throws Exception {
        JSONObject data = fetchLyricData(song);
        if (data == null) {
            return null;
        }

        final long songId = song.optLong("id");
        final String sourceUrl = "https://y.qq.com/n/ryqq/songDetail/" + songId;

        String original = decodeQqLyricPayload(data.optString("lyric", ""));
        Matcher qrcXmlMatcher = QRC_XML.matcher(original);
        if (qrcXmlMatcher.find()) {
            original = decodeXmlEntities(qrcXmlMatcher.group(1));
        }

        long[] offsetHolder = new long[1];
        List<String> qrcMetadata = parseQrcMetadata(original, offsetHolder);
        long qrcOffsetMs = offsetHolder[0];

        List<LyricsLine> lines = parseQrcFormat(original);
        if (lines.isEmpty()) {
            lines = LrcParser.parseSynced(original);
        }
        if (lines.isEmpty()) {
            return null;
        }

        if (qrcOffsetMs != 0) {
            lines = applyOffset(lines, qrcOffsetMs);
        }

        if (lines.isEmpty()) {
            return null;
        }

        String romaPayload = decodeQqLyricPayload(data.optString("roma", ""));
        List<LyricsLine> romaLines = romaPayload.isEmpty() ? null : parseQrcFormat(romaPayload);
        List<LyricsLine> romanization = LyricsMerge.mergeRomanization(lines, romaLines);

        String transPayload = decodeQqLyricPayload(data.optString("trans", ""));
        List<LyricsLine> transLines = transPayload.isEmpty() ? null : parseQrcFormat(transPayload);
        if (transLines == null || transLines.isEmpty()) {
            transLines = LrcParser.parseSynced(transPayload);
        }
        if (transLines != null) {
            transLines.removeIf(line -> "//".equals(line.text().trim()));
        }
        List<LyricsLine> translation = LyricsMerge.mergeRomanization(lines, transLines);
        Map<String, List<LyricsLine>> translations =
                LyricsMerge.singleLanguageTranslations(translation, "zh");

        return new Lyrics(lines, name(), true, romanization, translations, null,
                qrcMetadata.isEmpty() ? null : qrcMetadata, original, "qrc", sourceUrl);
    }

    @Nullable
    private static JSONObject searchBest(String keyword, TrackInfo track) throws IOException, JSONException {
        List<JSONObject> songs = searchAll(keyword, track);
        if (songs.isEmpty()) {
            return null;
        }
        JSONObject best = null;
        int bestScore = -1;
        for (JSONObject item : songs) {
            int score = scoreCandidate(item, track);
            if (score > bestScore) {
                bestScore = score;
                best = item;
            }
        }
        return best;
    }

    private static List<JSONObject> searchAll(String keyword, TrackInfo track)
            throws IOException, JSONException {
        JSONObject comm = musicuComm();

        JSONObject param = new JSONObject();
        param.put("search_id", System.currentTimeMillis());
        param.put("remoteplace", "search.android.keyboard");
        param.put("query", keyword);
        param.put("search_type", 0);
        param.put("num_per_page", 5);
        param.put("page_num", 1);
        param.put("highlight", 0);
        param.put("nqc_flag", 0);
        param.put("page_id", 1);
        param.put("grp", 1);

        JSONObject req0 = new JSONObject();
        req0.put("module", "music.search.SearchCgiService");
        req0.put("method", "DoSearchForQQMusicLite");
        req0.put("param", param);

        JSONObject payload = new JSONObject();
        payload.put("comm", comm);
        payload.put("req_0", req0);

        HttpURLConnection connection = LyricsRequests.postJson(MUSICU_URL, payload.toString());
        if (connection.getResponseCode() != 200) {
            LyricsRequests.logFailure("QQ", connection);
            return new ArrayList<>();
        }

        JSONObject response = Requester.parseJSONObject(connection);
        JSONArray songs = response.optJSONObject("req_0")
                .optJSONObject("data")
                .optJSONObject("body")
                .optJSONArray("item_song");
        if (songs == null || songs.length() == 0) {
            return new ArrayList<>();
        }

        List<JSONObject> scored = new ArrayList<>();
        for (int i = 0; i < songs.length(); i++) {
            JSONObject item = songs.optJSONObject(i);
            if (item != null) {
                scored.add(item);
            }
        }
        scored.sort((a, b) -> scoreCandidate(b, track) - scoreCandidate(a, track));
        return scored;
    }

    private static int scoreCandidate(JSONObject item, TrackInfo track) {
        String title = item.optString("title", "").toLowerCase(Locale.ROOT);
        String artist = singers(item).toLowerCase(Locale.ROOT);
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
            int duration = item.optInt("interval", 0);
            if (duration > 0 && Math.abs(duration - track.durationSeconds()) <= 5) {
                score += 2;
            }
        }
        return score;
    }

    private static String singers(JSONObject item) {
        JSONArray singers = item.optJSONArray("singer");
        if (singers == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < singers.length(); i++) {
            JSONObject singer = singers.optJSONObject(i);
            if (singer == null) {
                continue;
            }
            String name = singer.optString("name", "");
            if (!name.isEmpty()) {
                if (builder.length() > 0) {
                    builder.append('/');
                }
                builder.append(name);
            }
        }
        return builder.toString();
    }

    @Nullable
    private static JSONObject fetchLyricData(JSONObject song) throws IOException, JSONException {
        JSONObject comm = musicuComm();

        long songId = song.optLong("id");
        String title = song.optString("title", "");
        JSONObject album = song.optJSONObject("album");
        String albumName = album != null ? album.optString("name", "") : "";
        String singerName = singers(song);

        JSONObject param = new JSONObject();
        param.put("songID", songId);
        param.put("songName", base64Text(title));
        param.put("albumName", base64Text(albumName));
        param.put("singerName", base64Text(singerName));
        param.put("crypt", 1);
        param.put("qrc", 1);
        param.put("trans", 1);
        param.put("roma", 1);
        param.put("cv", 2111);
        param.put("ct", 19);
        param.put("lrc_t", 0);
        param.put("qrc_t", 0);
        param.put("roma_t", 0);
        param.put("trans_t", 0);
        param.put("type", 0);
        param.put("interval", song.optInt("interval", 0));

        JSONObject req0 = new JSONObject();
        req0.put("module", "music.musichallSong.PlayLyricInfo");
        req0.put("method", "GetPlayLyricInfo");
        req0.put("param", param);

        JSONObject payload = new JSONObject();
        payload.put("comm", comm);
        payload.put("req_0", req0);

        HttpURLConnection connection = LyricsRequests.postJson(MUSICU_URL, payload.toString());
        if (connection.getResponseCode() != 200) {
            LyricsRequests.logFailure("QQ", connection);
            return null;
        }

        JSONObject response = Requester.parseJSONObject(connection);
        JSONObject respReq0 = response.optJSONObject("req_0");
        JSONObject data = respReq0 == null ? null : respReq0.optJSONObject("data");
        return data;
    }

    private static String base64Text(String text) {
        try {
            return Base64.encodeToString(text.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        } catch (Exception ignored) {
            return "";
        }
    }

    /**
     * Decrypts a QQ lyric payload. QRC payloads are triple-DES encrypted and zlib
     * compressed; plain LRC payloads are base64 encoded instead.
     */
    private static String decodeQqLyricPayload(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }

        byte[] bytes = LyricsCrypto.hexToBytes(raw);
        if (bytes.length > 0 && bytes.length % 8 == 0) {
            String inflated = LyricsCrypto.inflate(LyricsCrypto.tripleDesEcbDecrypt(bytes, QRC_KEY));
            if (!inflated.isEmpty()) {
                return inflated;
            }
        }

        try {
            byte[] decoded = Base64.decode(raw, Base64.DEFAULT);
            if (decoded.length > 0) {
                return new String(decoded, StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) {
        }
        return raw;
    }

    private static String decodeXmlEntities(String text) {
        Matcher matcher = NUMERIC_ENTITY.matcher(text);
        StringBuilder builder = new StringBuilder();
        int last = 0;
        while (matcher.find()) {
            builder.append(text, last, matcher.start());
            try {
                builder.append((char) Integer.parseInt(matcher.group(1)));
            } catch (NumberFormatException ignored) {
                builder.append(matcher.group(0));
            }
            last = matcher.end();
        }
        builder.append(text, last, text.length());
        return builder.toString()
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&");
    }

    private static List<String> parseQrcMetadata(String text, long[] offsetOut) {
        List<String> metadata = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return metadata;
        }
        for (String rawLine : text.split("\\r?\\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            Matcher m = QRC_META.matcher(line);
            if (!m.matches()) {
                continue;
            }
            String key = m.group(1);
            String value = m.group(2);
            if ("offset".equalsIgnoreCase(key)) {
                try {
                    offsetOut[0] = Long.parseLong(value);
                } catch (NumberFormatException ignored) {
                }
                continue;
            }
            if (LRC_CREDIT_META_KEYS.contains(key.toLowerCase(Locale.ROOT))) {
                String trimmed = value.trim();
                if (!trimmed.isEmpty()) {
                    metadata.add(key + ":" + trimmed);
                }
            }
        }
        return metadata;
    }

    private static List<LyricsLine> applyOffset(List<LyricsLine> lines, long offsetMs) {
        List<LyricsLine> adjusted = new ArrayList<>(lines.size());
        for (LyricsLine line : lines) {
            long newStart = line.startTimeMs() + offsetMs;
            long newEnd = line.endTimeMs() != LyricsLine.NO_TIME
                    ? line.endTimeMs() + offsetMs : LyricsLine.NO_TIME;
            List<Word> adjustedWords = new ArrayList<>(line.words().size());
            for (Word word : line.words()) {
                adjustedWords.add(new Word(
                        word.startMs() + offsetMs,
                        word.endMs() + offsetMs,
                        word.text(),
                        word.romaji(),
                        word.endsWithSpace()));
            }
            adjusted.add(new LyricsLine(newStart, newEnd, line.text(), adjustedWords,
                    line.agentId(), line.isDuet(), line.isBG(), line.songPart()));
        }
        return adjusted;
    }

    private static List<LyricsLine> parseQrcFormat(String text) {
        List<LyricsLine> lines = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return lines;
        }

        String content = text;
        Matcher xml = QRC_XML.matcher(text);
        if (xml.find()) {
            content = decodeXmlEntities(xml.group(1));
        }

        for (String rawLine : content.split("\\r?\\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }

            if (QRC_META.matcher(line).matches()) {
                continue;
            }

            Matcher lineMatch = QRC_LINE.matcher(line);
            if (!lineMatch.matches()) {
                continue;
            }

            long lineStart = Long.parseLong(lineMatch.group(1));
            long lineDuration = Long.parseLong(lineMatch.group(2));
            long lineEnd = lineStart + lineDuration;
            String lineContent = lineMatch.group(3);

            if (QRC_WHOLE_LINE_COMMENT.matcher(lineContent).find()) {
                continue;
            }

            List<Long> offsets = new ArrayList<>();
            List<String> texts = new ArrayList<>();
            Matcher wordMatch = QRC_WORD.matcher(lineContent);
            int prevEnd = 0;
            while (wordMatch.find()) {
                offsets.add(Long.parseLong(wordMatch.group(1)));
                texts.add(lineContent.substring(prevEnd, wordMatch.start()));
                prevEnd = wordMatch.end();
            }
            if (!offsets.isEmpty()) {
                texts.add(lineContent.substring(prevEnd));
            }

            List<Word> words = new ArrayList<>();
            StringBuilder full = new StringBuilder();
            for (int i = 0; i < offsets.size(); i++) {
                long wordStart = offsets.get(i);
                long wordEnd = (i < offsets.size() - 1) ? offsets.get(i + 1) : lineEnd;
                String wordText = texts.get(i);
                if (wordText.isEmpty()) {
                    continue;
                }
                boolean endsWithSpace = !wordText.isEmpty()
                        && Character.isWhitespace(wordText.charAt(wordText.length() - 1));
                words.add(new Word(wordStart, wordEnd, wordText.trim(), null, endsWithSpace));
                full.append(wordText);
            }
            if (words.isEmpty() && !lineContent.isEmpty()) {
                String stripped = QRC_WORD.matcher(lineContent).replaceAll("").trim();
                if (!stripped.isEmpty()) {
                    words.add(new Word(lineStart, lineEnd, stripped));
                    full.append(stripped);
                }
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
}
