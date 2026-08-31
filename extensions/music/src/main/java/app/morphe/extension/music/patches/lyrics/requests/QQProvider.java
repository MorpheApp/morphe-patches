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
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.music.patches.lyrics.LrcParser;
import app.morphe.extension.music.patches.lyrics.Lyrics;
import app.morphe.extension.music.patches.lyrics.LyricsLine;
import app.morphe.extension.music.patches.lyrics.TrackInfo;
import app.morphe.extension.music.patches.lyrics.Word;
import app.morphe.extension.shared.Logger;
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

    @Override
    public String name() {
        return "QQ";
    }

    @Nullable
    @Override
    public Lyrics fetch(TrackInfo track) throws Exception {
        String keyword = track.title() + " " + track.artist();
        JSONObject song = searchBest(keyword, track);
        if (song == null || !song.has("id")) {
            return null;
        }

        JSONObject data = fetchLyricData(song);
        if (data == null) {
            return null;
        }

        String original = decodeQqLyricPayload(data.optString("lyric", ""));
        List<LyricsLine> lines = parseQrcFormat(original);
        if (lines.isEmpty()) {
            // Some tracks ship a plain LRC original instead of a QRC.
            lines = LrcParser.parseSynced(original);
        }
        if (lines.isEmpty()) {
            return null;
        }

        final List<LyricsLine> finalLines = lines;
        final TrackInfo finalTrack = track;
        Logger.printDebug(() -> "QQ returned " + finalLines.size()
                + " lines (wordSynced=" + hasWordTimings(finalLines) + ") for " + finalTrack);
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
    private static JSONObject searchBest(String keyword, TrackInfo track) throws IOException, JSONException {
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
            return null;
        }

        JSONObject response = Requester.parseJSONObject(connection);
        JSONArray songs = response.optJSONObject("req_0")
                .optJSONObject("data")
                .optJSONObject("body")
                .optJSONArray("item_song");
        Logger.printDebug(() -> "QQ search item_song=" + (songs == null ? "null" : songs.length()));
        if (songs == null || songs.length() == 0) {
            return null;
        }

        JSONObject best = null;
        int bestScore = -1;
        for (int i = 0; i < songs.length(); i++) {
            JSONObject item = songs.optJSONObject(i);
            if (item == null) {
                continue;
            }
            int score = scoreCandidate(item, track);
            if (score > bestScore) {
                bestScore = score;
                best = item;
            }
        }
        return best;
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
        Logger.printDebug(() -> "QQ fetchLyricData songID=" + songId
                + " req_0=" + (respReq0 == null ? "null" : "ok")
                + " hasData=" + (data != null)
                + " hasLyric=" + (data != null && data.has("lyric"))
                + " hasTrans=" + (data != null && data.has("trans"))
                + " hasRoma=" + (data != null && data.has("roma")));
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

            List<Long> offsets = new ArrayList<>();
            List<String> texts = new ArrayList<>();
            Matcher wordMatch = QRC_WORD.matcher(lineContent);
            int previousEnd = 0;
            while (wordMatch.find()) {
                texts.add(lineContent.substring(previousEnd, wordMatch.start()));
                offsets.add(Long.parseLong(wordMatch.group(1)));
                previousEnd = wordMatch.end();
            }

            List<Word> words = new ArrayList<>();
            StringBuilder full = new StringBuilder();
            for (int i = 0; i < offsets.size(); i++) {
                long wordStart = offsets.get(i);
                long wordEnd = (i < offsets.size() - 1) ? offsets.get(i + 1) : lineEnd;
                String wordText = texts.get(i);
                words.add(new Word(wordStart, wordEnd, wordText));
                full.append(wordText);
            }
            if (words.isEmpty() && !lineContent.isEmpty()) {
                words.add(new Word(lineStart, lineEnd, lineContent));
                full.append(lineContent);
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
