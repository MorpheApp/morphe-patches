/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2269
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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.zip.InflaterInputStream;

import app.morphe.extension.music.patches.lyrics.Lyrics;
import app.morphe.extension.music.patches.lyrics.LyricsLine;
import app.morphe.extension.music.patches.lyrics.LyricsMerge;
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


    @Override
    public String name() {
        return "KuGou";
    }

    @Override
    public boolean hasCandidates() {
        return true;
    }

    @Nullable
    @Override
    public FetchResult fetch(TrackInfo track) throws Exception {
        SongInfo songInfo = resolveHash(track);
        if (songInfo == null || songInfo.hash().isEmpty()) {
            return null;
        }
        String hash = songInfo.hash();
        String id = songInfo.id();
        if (id.isEmpty()) {
            id = hash;
        }

        String searchUrl = SEARCH_URL + LyricsRequests.encode(hash);
        HttpURLConnection searchConnection = LyricsRequests.openConnection(searchUrl);
        if (searchConnection.getResponseCode() != Requester.HTTP_STATUS_CODE_SUCCESS) {
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

        String candidateId = candidate.optString("id", "");
        String accessKey = candidate.optString("accesskey", "");
        if (candidateId.isEmpty() || accessKey.isEmpty()) {
            return null;
        }

        String downloadUrl = DOWNLOAD_URL + "&id=" + LyricsRequests.encode(candidateId) + "&accesskey=" + LyricsRequests.encode(accessKey);
        HttpURLConnection downloadConnection = LyricsRequests.openConnection(downloadUrl);
        if (downloadConnection.getResponseCode() != Requester.HTTP_STATUS_CODE_SUCCESS) {
            LyricsRequests.logFailure(name(), downloadConnection);
            return null;
        }

        JSONObject downloadResponse = Requester.parseJSONObject(downloadConnection);
        String content = downloadResponse.optString("content", "");
        if (content.isEmpty()) {
            return null;
        }

        byte[] raw = Base64.decode(content, Base64.DEFAULT);
        KrcResult krcResult;
        String rawFormat;
        String formatType;
        if (raw.length > 4 && raw[0] == 'k' && raw[1] == 'r' && raw[2] == 'c' && raw[3] == '1') {
            rawFormat = decryptKrc(raw);
            krcResult = parseKrc(rawFormat);
            formatType = "krc";
        } else {
            // Some tracks only expose plain LRC even when KRC is requested.
            rawFormat = new String(raw, StandardCharsets.UTF_8);
            List<String> metadataCreditLines = LrcParser.extractCreditMetadata(rawFormat);
            krcResult = new KrcResult(LrcParser.parseSynced(rawFormat), metadataCreditLines, null, null);
            formatType = "lrc";
        }
        List<LyricsLine> lines = krcResult.lines();
        List<String> creditLines = new ArrayList<>(krcResult.creditLines());
        if (lines.isEmpty()) {
            return null;
        }

        List<LyricsLine> romanization = LyricsMerge.mergeRomanization(lines, krcResult.romanization());
        List<LyricsLine> translation = LyricsMerge.mergeRomanization(lines, krcResult.translation());
        Map<String, List<LyricsLine>> translations =
                LyricsMerge.singleLanguageTranslations(translation, "zh");

        List<LyricsLine> attachedRomanization =
                isChineseLanguage() && LyricsMerge.hasText(romanization) ? romanization : null;

        String sourceUrl = "https://www.kugou.com/song/" + id + ".html";
        return FetchResult.of(new Lyrics(lines, name(), true, attachedRomanization, translations, null,
                creditLines.isEmpty() ? null : creditLines, rawFormat, formatType, sourceUrl), track);
    }

    @Override
    public List<Lyrics.ScoredLyrics> fetchCandidates(TrackInfo track) throws Exception {
        SongInfo songInfo = resolveHash(track);
        if (songInfo == null || songInfo.hash().isEmpty()) {
            return new ArrayList<>();
        }
        String hash = songInfo.hash();
        String id = songInfo.id();
        if (id.isEmpty()) {
            id = hash;
        }

        String searchUrl = SEARCH_URL + LyricsRequests.encode(hash);
        HttpURLConnection searchConnection = LyricsRequests.openConnection(searchUrl);
        if (searchConnection.getResponseCode() != Requester.HTTP_STATUS_CODE_SUCCESS) {
            return new ArrayList<>();
        }

        JSONObject searchResponse = Requester.parseJSONObject(searchConnection);
        JSONArray candidates = searchResponse.optJSONArray("candidates");
        if (candidates == null || candidates.length() == 0) {
            return new ArrayList<>();
        }

        List<Lyrics.ScoredLyrics> scored = new ArrayList<>();
        for (int i = 0; i < candidates.length(); i++) {
            if (scored.size() >= LyricsRequests.MAX_CANDIDATES) {
                break;
            }
            JSONObject candidate = candidates.optJSONObject(i);
            if (candidate == null) {
                continue;
            }
            try {
                String sourceUrl = "https://www.kugou.com/song/" + id + ".html";
                Lyrics lyrics = fetchFromCandidate(candidate, sourceUrl);
                if (lyrics != null) {
                    int score = LyricsRequests.scoreSingleResult(lyrics);
                    scored.add(new Lyrics.ScoredLyrics(score, lyrics));
                }
            } catch (Exception ex) {
                Logger.printDebug(() -> "Could not fetch KuGou lyrics for a candidate", ex);
            }
        }

        return Lyrics.sortScoredByScore(scored);
    }

    @Nullable
    private Lyrics fetchFromCandidate(JSONObject candidate, @Nullable String sourceUrl) throws Exception {
        String id = candidate.optString("id", "");
        String accessKey = candidate.optString("accesskey", "");
        if (id.isEmpty() || accessKey.isEmpty()) {
            return null;
        }

        String downloadUrl = DOWNLOAD_URL + "&id=" + LyricsRequests.encode(id) + "&accesskey=" + LyricsRequests.encode(accessKey);
        HttpURLConnection downloadConnection = LyricsRequests.openConnection(downloadUrl);
        if (downloadConnection.getResponseCode() != Requester.HTTP_STATUS_CODE_SUCCESS) {
            return null;
        }

        JSONObject downloadResponse = Requester.parseJSONObject(downloadConnection);
        String content = downloadResponse.optString("content", "");
        if (content.isEmpty()) {
            return null;
        }

        byte[] raw = Base64.decode(content, Base64.DEFAULT);
        KrcResult krcResult;
        String rawFormat;
        String formatType;
        if (raw.length > 4 && raw[0] == 'k' && raw[1] == 'r' && raw[2] == 'c' && raw[3] == '1') {
            rawFormat = decryptKrc(raw);
            krcResult = parseKrc(rawFormat);
            formatType = "krc";
        } else {
            rawFormat = new String(raw, StandardCharsets.UTF_8);
            List<String> metadataCreditLines = LrcParser.extractCreditMetadata(rawFormat);
            krcResult = new KrcResult(LrcParser.parseSynced(rawFormat), metadataCreditLines, null, null);
            formatType = "lrc";
        }
        List<LyricsLine> lines = krcResult.lines();
        List<String> creditLines = new ArrayList<>(krcResult.creditLines());
        if (lines.isEmpty()) {
            return null;
        }

        List<LyricsLine> romanization = LyricsMerge.mergeRomanization(lines, krcResult.romanization());
        List<LyricsLine> translation = LyricsMerge.mergeRomanization(lines, krcResult.translation());
        Map<String, List<LyricsLine>> translations =
                LyricsMerge.singleLanguageTranslations(translation, "zh");

        List<LyricsLine> attachedRomanization =
                isChineseLanguage() && LyricsMerge.hasText(romanization) ? romanization : null;

        return new Lyrics(lines, name(), true, attachedRomanization, translations, null,
                creditLines.isEmpty() ? null : creditLines, rawFormat, formatType, sourceUrl);
    }

    private static boolean isChineseLanguage() {
        return "zh".equals(LyricsRequests.deviceLanguage());
    }

    @Nullable
    private static SongInfo resolveHash(TrackInfo track) throws IOException, JSONException {
        String keyword = track.artist() + " " + track.title();
        String url = SONG_SEARCH_URL + "&keyword=" + LyricsRequests.encode(keyword);
        HttpURLConnection connection = LyricsRequests.openConnection(url);
        if (connection.getResponseCode() != Requester.HTTP_STATUS_CODE_SUCCESS) {
            LyricsRequests.logFailure("KuGou", connection);
            return null;
        }

        JSONObject root = Requester.parseJSONObject(connection);
        JSONObject data = root.optJSONObject("data");
        JSONArray info = data == null ? null : data.optJSONArray("info");
        if (info == null || info.length() == 0) {
            return null;
        }

        String bestHash = null;
        String bestId = null;
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

            String title = item.optString("songname", "");
            String artist = item.optString("singername", "");
            int score = LyricsRequests.scoreTrackCandidate(title, artist,
                    item.optInt("duration", 0), track);
            if (score > bestScore) {
                bestScore = score;
                bestHash = hash;
                bestId = item.optString("id", "");
            }
        }
        if (bestHash == null || bestScore < LyricsRequests.SOFT_MIN) {
            return null;
        }
        return new SongInfo(bestHash, bestId);
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
        //noinspection CharsetObjectCanBeUsed
        return out.toString(StandardCharsets.UTF_8.name());
    }

    private record SongInfo(String hash, String id) {
        SongInfo {
            id = id != null ? id : "";
        }
    }

    private record KrcResult(List<LyricsLine> lines, List<String> creditLines,
                             @Nullable List<LyricsLine> romanization,
                             @Nullable List<LyricsLine> translation) {
        KrcResult {
            creditLines = creditLines != null ? creditLines : List.of();
        }
    }

    /** Romanization (type 0) and translation (type 1) extracted from a KRC {@code [language]} tag. */
    private record KrcAuxiliary(@Nullable List<LyricsLine> romanization,
                                @Nullable List<LyricsLine> translation) {
    }

    private static KrcResult parseKrc(String krc) {
        List<String> creditLines = new ArrayList<>();
        if (krc == null || krc.isEmpty()) {
            return new KrcResult(new ArrayList<>(), creditLines, null, null);
        }

        long fileOffsetMs = 0;
        String languageTag = null;
        for (String rawLine : krc.split("\\r?\\n")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.charAt(0) != '[') {
                continue;
            }

            Matcher meta = LrcParser.LRC_META.matcher(line);
            if (meta.matches()) {
                String key = meta.group(1);
                String value = meta.group(2);
                if (key == null || value == null) {
                    continue;
                }
                String name = key.toLowerCase(Locale.ROOT);
                if (name.equals("offset")) {
                    try {
                        fileOffsetMs = -Long.parseLong(value.trim());
                    } catch (NumberFormatException ex) {
                        Logger.printDebug(() -> "Could not parse offset in KuGou LRC", ex);
                    }
                } else if (name.equals("language")) {
                    languageTag = value;
                } else if (LrcParser.CREDIT_META_KEYS.contains(name)) {
                    String trimmed = value.trim();
                    if (!trimmed.isEmpty()) {
                        creditLines.add(key + ":" + trimmed);
                    }
                }
            }
        }

        List<LyricsLine> lines = applyFileOffset(KrcParser.parse(krc), fileOffsetMs);
        KrcAuxiliary auxiliary = languageTag == null ? null : parseKrcLanguageTag(languageTag, lines);
        return new KrcResult(lines, creditLines,
                auxiliary == null ? null : auxiliary.romanization(),
                auxiliary == null ? null : auxiliary.translation());
    }

     /**
     * Applies the file's {@code [offset]} tag, which belongs to the lyrics themselves rather
     * than to the offset the user configures. The caller has already negated it, because a
     * positive tag means the lyrics are shown earlier.
     */
    private static List<LyricsLine> applyFileOffset(List<LyricsLine> lines, long offsetMs) {
        if (offsetMs == 0) {
            return lines;
        }
        List<LyricsLine> shifted = new ArrayList<>(lines.size());
        for (LyricsLine line : lines) {
            List<Word> words = new ArrayList<>(line.words().size());
            for (Word word : line.words()) {
                words.add(new Word(shift(word.startMs(), offsetMs), shift(word.endMs(), offsetMs),
                        word.text(), word.romaji(), word.endsWithSpace()));
            }
            shifted.add(new LyricsLine(shift(line.startTimeMs(), offsetMs),
                    shift(line.endTimeMs(), offsetMs), line.text(), words,
                    line.agentId(), line.isDuet(), line.isBG(), line.songPart()));
        }
        return shifted;
    }

    /** Leaves {@link LyricsLine#NO_TIME} alone and keeps a shift from going negative. */
    private static long shift(long timeMs, long offsetMs) {
        return timeMs == LyricsLine.NO_TIME ? timeMs : Math.max(0, timeMs + offsetMs);
    }

    @Nullable
    private static KrcAuxiliary parseKrcLanguageTag(String tag, List<LyricsLine> original) {
        if (tag.isEmpty()) {
            return null;
        }
        try {
            String decoded = new String(Base64.decode(tag, Base64.DEFAULT), StandardCharsets.UTF_8);
            JSONObject root = new JSONObject(decoded);
            JSONArray content = root.optJSONArray("content");
            if (content == null) {
                return null;
            }

            JSONArray romaContent = null;
            JSONArray transContent = null;
            for (int i = 0; i < content.length(); i++) {
                JSONObject item = content.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                int type = item.optInt("type", -1);
                if (type == 0) {
                    romaContent = item.optJSONArray("lyricContent");
                } else if (type == 1) {
                    transContent = item.optJSONArray("lyricContent");
                }
            }

            List<LyricsLine> romaLines = null;
            if (romaContent != null) {
                romaLines = new ArrayList<>();
                int skippedEmpty = 0;
                for (int li = 0; li < original.size(); li++) {
                    LyricsLine line = original.get(li);
                    if (!lineHasText(line)) {
                        skippedEmpty++;
                        continue;
                    }
                    int contentIndex = li - skippedEmpty;
                    if (contentIndex >= 0 && contentIndex < romaContent.length()) {
                        String text = joinKrcRomaEntry(romaContent.optJSONArray(contentIndex));
                        if (!text.isEmpty()) {
                            romaLines.add(new LyricsLine(line.startTimeMs(), text));
                        }
                    }
                }
                if (romaLines.isEmpty()) {
                    romaLines = null;
                }
            }

            List<LyricsLine> transLines = null;
            if (transContent != null) {
                transLines = new ArrayList<>();
                for (int li = 0; li < original.size(); li++) {
                    LyricsLine line = original.get(li);
                    if (li < transContent.length()) {
                        JSONArray entry = transContent.optJSONArray(li);
                        String text = (entry != null && entry.length() > 0) ? entry.optString(0, "") : "";
                        if (text != null && !text.isEmpty()) {
                            transLines.add(new LyricsLine(line.startTimeMs(), text));
                        }
                    }
                }
                if (transLines.isEmpty()) {
                    transLines = null;
                }
            }

            if (romaLines == null && transLines == null) {
                return null;
            }
            return new KrcAuxiliary(romaLines, transLines);
        } catch (Exception ex) {
            return null;
        }
    }

    private static boolean lineHasText(LyricsLine line) {
        for (Word word : line.words()) {
            if (word.text() != null && !word.text().trim().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static String joinKrcRomaEntry(@Nullable JSONArray entry) {
        if (entry == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0, length = entry.length(); i < length; i++) {
            String part = entry.optString(i, "").trim();
            if (!part.isEmpty()) {
                //noinspection SizeReplaceableByIsEmpty
                if (builder.length() > 0) {
                    builder.append(' ');
                }
                builder.append(part);
            }
        }
        return builder.toString();
    }
}
