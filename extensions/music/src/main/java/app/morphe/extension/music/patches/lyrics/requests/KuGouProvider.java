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

import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.InflaterInputStream;

import app.morphe.extension.music.patches.lyrics.LrcParser;
import app.morphe.extension.music.patches.lyrics.Lyrics;
import app.morphe.extension.music.patches.lyrics.LyricsLine;
import app.morphe.extension.music.patches.lyrics.LyricsMerge;
import app.morphe.extension.music.patches.lyrics.TrackInfo;
import app.morphe.extension.music.patches.lyrics.Word;
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

    private static final java.util.Set<String> LRC_CREDIT_META_KEYS =
            java.util.Set.of("ti", "ar", "al", "au");

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
    public Lyrics fetch(TrackInfo track) throws Exception {
        SongInfo songInfo = resolveHash(track);
        if (songInfo == null || songInfo.hash.isEmpty()) {
            return null;
        }
        String hash = songInfo.hash;
        String id = songInfo.id;
        if (id.isEmpty()) {
            id = hash;
        }

        String searchUrl = SEARCH_URL + LyricsRequests.encode(hash);
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

        String candidateId = candidate.optString("id", "");
        String accessKey = candidate.optString("accesskey", "");
        if (candidateId.isEmpty() || accessKey.isEmpty()) {
            return null;
        }

        String downloadUrl = DOWNLOAD_URL + "&id=" + LyricsRequests.encode(candidateId) + "&accesskey=" + LyricsRequests.encode(accessKey);
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
            List<String> metadataCreditLines = extractLrcMetadata(rawFormat);
            krcResult = new KrcResult(LrcParser.parseSynced(rawFormat), metadataCreditLines, null, null);
            formatType = "lrc";
        }
        List<LyricsLine> lines = krcResult.lines;
        List<String> creditLines = new ArrayList<>(krcResult.creditLines);
        if (lines.isEmpty()) {
            return null;
        }

        List<LyricsLine> romanization = LyricsMerge.mergeRomanization(lines, krcResult.romanization);
        List<LyricsLine> translation = LyricsMerge.mergeRomanization(lines, krcResult.translation);
        Map<String, List<LyricsLine>> translations =
                LyricsMerge.singleLanguageTranslations(translation, "zh");

        final List<LyricsLine> attachedRomanization =
                isChineseLanguage() && LyricsMerge.hasText(romanization) ? romanization : null;

        String sourceUrl = "https://www.kugou.com/song/" + id + ".html";
        return new Lyrics(lines, name(), true, attachedRomanization, translations, null,
                creditLines.isEmpty() ? null : creditLines, rawFormat, formatType, sourceUrl);
    }

    @Override
    public List<Lyrics> fetchCandidates(TrackInfo track) throws Exception {
        SongInfo songInfo = resolveHash(track);
        if (songInfo == null || songInfo.hash.isEmpty()) {
            return new ArrayList<>();
        }
        String hash = songInfo.hash;
        String id = songInfo.id;
        if (id.isEmpty()) {
            id = hash;
        }

        String searchUrl = SEARCH_URL + LyricsRequests.encode(hash);
        HttpURLConnection searchConnection = LyricsRequests.openConnection(searchUrl);
        if (searchConnection.getResponseCode() != 200) {
            return new ArrayList<>();
        }

        JSONObject searchResponse = Requester.parseJSONObject(searchConnection);
        JSONArray candidates = searchResponse.optJSONArray("candidates");
        if (candidates == null || candidates.length() == 0) {
            return new ArrayList<>();
        }

        List<Lyrics> results = new ArrayList<>();
        for (int i = 0; i < candidates.length(); i++) {
            if (results.size() >= 5) {
                break;
            }
            JSONObject candidate = candidates.optJSONObject(i);
            if (candidate == null) {
                continue;
            }
            try {
                String sourceUrl = "https://www.kugou.com/song/" + id + ".html";
                Lyrics lyrics = fetchFromCandidate(candidate, track, sourceUrl);
                if (lyrics != null) {
                    results.add(lyrics);
                }
            } catch (Exception ex) {
            }
        }
        return results;
    }

    @Nullable
    private Lyrics fetchFromCandidate(JSONObject candidate, TrackInfo track, @Nullable String sourceUrl) throws Exception {
        String id = candidate.optString("id", "");
        String accessKey = candidate.optString("accesskey", "");
        if (id.isEmpty() || accessKey.isEmpty()) {
            return null;
        }

        String downloadUrl = DOWNLOAD_URL + "&id=" + LyricsRequests.encode(id) + "&accesskey=" + LyricsRequests.encode(accessKey);
        HttpURLConnection downloadConnection = LyricsRequests.openConnection(downloadUrl);
        if (downloadConnection.getResponseCode() != 200) {
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
            List<String> metadataCreditLines = extractLrcMetadata(rawFormat);
            krcResult = new KrcResult(LrcParser.parseSynced(rawFormat), metadataCreditLines, null, null);
            formatType = "lrc";
        }
        List<LyricsLine> lines = krcResult.lines;
        List<String> creditLines = new ArrayList<>(krcResult.creditLines);
        if (lines.isEmpty()) {
            return null;
        }

        List<LyricsLine> romanization = LyricsMerge.mergeRomanization(lines, krcResult.romanization);
        List<LyricsLine> translation = LyricsMerge.mergeRomanization(lines, krcResult.translation);
        Map<String, List<LyricsLine>> translations =
                LyricsMerge.singleLanguageTranslations(translation, "zh");

        final List<LyricsLine> attachedRomanization =
                isChineseLanguage() && LyricsMerge.hasText(romanization) ? romanization : null;

        return new Lyrics(lines, name(), true, attachedRomanization, translations, null,
                creditLines.isEmpty() ? null : creditLines, rawFormat, formatType, sourceUrl);
    }

    private static boolean isChineseLanguage() {
        return "zh".equals(Locale.getDefault().getLanguage());
    }

    @Nullable
    private static SongInfo resolveHash(TrackInfo track) throws IOException, JSONException {
        String keyword = track.artist() + " " + track.title();
        String url = SONG_SEARCH_URL + "&keyword=" + LyricsRequests.encode(keyword);
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
                bestId = item.optString("id", "");
            }
        }
        return bestHash != null ? new SongInfo(bestHash, bestId) : null;
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

    private static final class SongInfo {
        final String hash;
        final String id;

        SongInfo(String hash, String id) {
            this.hash = hash;
            this.id = id != null ? id : "";
        }
    }

    private static final class KrcResult {
        final List<LyricsLine> lines;
        final List<String> creditLines;
        @Nullable
        final List<LyricsLine> romanization;
        @Nullable
        final List<LyricsLine> translation;

        KrcResult(List<LyricsLine> lines, @Nullable List<String> creditLines,
                  @Nullable List<LyricsLine> romanization,
                  @Nullable List<LyricsLine> translation) {
            this.lines = lines;
            this.creditLines = creditLines != null ? creditLines : List.of();
            this.romanization = romanization;
            this.translation = translation;
        }
    }

    /** Romanization (type 0) and translation (type 1) extracted from a KRC {@code [language]} tag. */
    private static final class KrcAuxiliary {
        @Nullable
        final List<LyricsLine> romanization;
        @Nullable
        final List<LyricsLine> translation;

        KrcAuxiliary(@Nullable List<LyricsLine> romanization, @Nullable List<LyricsLine> translation) {
            this.romanization = romanization;
            this.translation = translation;
        }
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

            Matcher meta = KRC_META.matcher(line);
            if (meta.matches()) {
                String name = meta.group(1).toLowerCase(Locale.ROOT);
                if (name.equals("offset")) {
                    try {
                        fileOffsetMs = -Long.parseLong(meta.group(2).trim());
                    } catch (NumberFormatException ignored) {
                    }
                } else if (name.equals("language")) {
                    languageTag = meta.group(2);
                } else {
                    if (LRC_CREDIT_META_KEYS.contains(name)) {
                        String value = meta.group(2).trim();
                        if (!value.isEmpty()) {
                            creditLines.add(meta.group(1) + ":" + value);
                        }
                    }
                }
            }
        }

        List<LyricsLine> lines = KrcParser.parse(krc);
        KrcAuxiliary auxiliary = languageTag == null ? null : parseKrcLanguageTag(languageTag, lines);
        return new KrcResult(lines, creditLines,
                auxiliary == null ? null : auxiliary.romanization,
                auxiliary == null ? null : auxiliary.translation);
    }

    private static List<String> extractLrcMetadata(String lrc) {
        List<String> creditLines = new ArrayList<>();
        if (lrc == null || lrc.isEmpty()) {
            return creditLines;
        }
        for (String rawLine : lrc.split("\\r?\\n")) {
            String line = rawLine.trim();
            Matcher meta = KRC_META.matcher(line);
            if (meta.matches()) {
                String name = meta.group(1).toLowerCase(Locale.ROOT);
                if (!name.equals("offset") && LRC_CREDIT_META_KEYS.contains(name)) {
                    String value = meta.group(2).trim();
                    if (!value.isEmpty()) {
                        creditLines.add(meta.group(1) + ":" + value);
                    }
                }
            }
        }
        return creditLines;
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
        for (int i = 0; i < entry.length(); i++) {
            String part = entry.optString(i, "").trim();
            if (!part.isEmpty()) {
                if (builder.length() > 0) {
                    builder.append(' ');
                }
                builder.append(part);
            }
        }
        return builder.toString();
    }
}