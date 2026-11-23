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
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import app.morphe.extension.music.patches.lyrics.model.Lyrics;
import app.morphe.extension.music.patches.lyrics.model.LyricsLine;
import app.morphe.extension.music.patches.lyrics.model.TrackInfo;
import app.morphe.extension.music.patches.lyrics.model.Word;
import app.morphe.extension.music.patches.lyrics.parsers.TTMLParser;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.requests.Requester;

public final class LyricsPlusProvider implements LyricsProvider {

    /** The official server first, then the mirrors the official client lists. */
    static final String[] API_HOSTS = {
            "https://lyricsplus.prjktla.my.id",
            "https://lyricsplus.binimum.org",
            "https://lyricsplus.prjktla.workers.dev",
    };

    private static final String BROWSER_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    @Nullable
    private static LyricsLine buildLineFromCandidates(
            List<WordCandidate> candidates,
            long lineStart,
            long lineEnd,
            long lineDuration,
            @Nullable String rawFallbackText,
            @Nullable String agentId,
            boolean isDuet,
            boolean isBackground,
            @Nullable String songPart
    ) {
        if (candidates.isEmpty()) {
            return null;
        }

        List<Word> words = new ArrayList<>(candidates.size());
        StringBuilder textBuf = new StringBuilder();

        for (WordCandidate candidate : candidates) {
            long startTime = candidate.syl.optLong("time");
            long duration = candidate.syl.optLong("duration");
            long endTime = startTime + duration;
            String text = candidate.syl.optString("text", "");
            boolean endsWithSpace = text.endsWith(" ") || text.endsWith("\t");

            String cleanWord = isBackground ? text.replaceAll("[()（）]", "").trim() : text.trim();
            String cleanRoma = candidate.romaji;
            if (isBackground && cleanRoma != null) {
                cleanRoma = cleanRoma.replaceAll("[()（）]", "").trim();
            }

            if (!cleanWord.isEmpty()) {
                words.add(new Word(startTime, endTime, cleanWord, cleanRoma, endsWithSpace));
                textBuf.append(cleanWord);
                if (endsWithSpace) {
                    textBuf.append(' ');
                }
            }
        }

        String fullText = textBuf.toString().trim();

        if (fullText.indexOf(' ') < 0 && words.size() > 1 && !TTMLParser.isContinuousCjk(words.get(0).text())) {
            StringBuilder reconstructed = new StringBuilder();
            for (int i = 0; i < words.size(); i++) {
                Word word = words.get(i);
                if (i > 0) {
                    reconstructed.append(' ');
                }
                reconstructed.append(word.text());
                boolean hasSpace = (i < words.size() - 1);
                words.set(i, new Word(word.startMs(), word.endMs(), word.text(), word.romaji(), hasSpace, word.ruby()));
            }
            fullText = reconstructed.toString();
        }

        if (!words.isEmpty()) {
            Word lastWord = words.get(words.size() - 1);
            if (lastWord.endsWithSpace()) {
                words.set(words.size() - 1, new Word(lastWord.startMs(), lastWord.endMs(), lastWord.text(), lastWord.romaji(), false, lastWord.ruby()));
            }
        }

        if (fullText.isEmpty() && rawFallbackText != null && !rawFallbackText.isEmpty()) {
            fullText = rawFallbackText.trim();
        }

        if (fullText.isEmpty() && words.isEmpty()) {
            return null;
        }

        long start = words.isEmpty() ? lineStart : words.get(0).startMs();
        long end = words.isEmpty() ? lineEnd : words.get(words.size() - 1).endMs();
        if (end < start && lineDuration > 0) {
            end = start + lineDuration;
        }

        return new LyricsLine(start, end, fullText, words, agentId, isDuet, isBackground, songPart);
    }

    private static boolean isAgentDuet(@Nullable String agentId) {
        if (agentId == null || !agentId.matches("v\\d{1,6}")) {
            return false;
        }
        int num = Integer.parseInt(agentId.substring(1));
        return num > 0 && num % 2 == 0;
    }

    @Override
    public String name() {
        return "LyricsPlus";
    }

    @Override
    public boolean hasCandidates() {
        return true;
    }

    @Nullable
    @Override
    public FetchResult fetch(TrackInfo track) {
        if (track.title().isEmpty() || track.artist().isEmpty()) {
            return null;
        }

        Lyrics lyrics = fetchFromApi(track);
        if (lyrics != null && !lyrics.lines().isEmpty()) {
            return FetchResult.of(lyrics, track);
        }

        final String cleanTitle = track.title()
                .replace("(Official Video)", "")
                .replace("(Official Music Video)", "")
                .replace("(Official Audio)", "")
                .trim();
        if (!cleanTitle.equals(track.title())) {
            TrackInfo cleanTrack = new TrackInfo(cleanTitle, track.artist(), track.album(), track.durationSeconds());
            lyrics = fetchFromApi(cleanTrack);
            if (lyrics != null && !lyrics.lines().isEmpty()) {
                return FetchResult.of(lyrics, cleanTrack);
            }
        }

        return null;
    }

    @Nullable
    @Override
    public List<Lyrics.ScoredLyrics> fetchCandidates(TrackInfo track) throws Exception {
        JSONArray results = searchCandidates(track);
        if (results == null || results.length() == 0) {
            return new ArrayList<>();
        }
        List<Lyrics.ScoredLyrics> scored = new ArrayList<>();
        for (int i = 0; i < results.length(); i++) {
            if (scored.size() >= LyricsRequests.MAX_CANDIDATES) {
                break;
            }
            JSONObject item = results.optJSONObject(i);
            if (item == null) continue;
            String candTitle = item.optString("title", "");
            String candArtist = item.optString("artist", "");
            long durMs = item.optLong("durationMs", 0);
            int durSec = durMs > 0 ? (int) (durMs / 1000) : 0;
            Lyrics lyrics;
            try {
                lyrics = fetchCandidateLyrics(item);
            } catch (Exception ex) {
                Logger.printDebug(() -> "LyricsPlus candidate lyrics failed", ex);
                continue;
            }
            if (lyrics == null || lyrics.lines().isEmpty()) continue;
            int score = LyricsRequests.scoreLyricsCandidate(candTitle, candArtist, durSec, lyrics, track);
            if (score < LyricsRequests.SOFT_MIN) continue;
            scored.add(new Lyrics.ScoredLyrics(score, lyrics));
        }
        return Lyrics.sortScoredByScore(scored);
    }

    @Nullable
    private Lyrics fetchFromApi(TrackInfo track) {
        for (String host : API_HOSTS) {
            final String currentHost = host;
            try {
                Lyrics lyrics = fetchFromHost(currentHost, track);
                if (lyrics != null) {
                    return lyrics;
                }
            } catch (Exception ex) {
                Logger.printDebug(() -> "LyricsPlus fetch failed: " + currentHost, ex);
            }
        }
        return null;
    }

    @Nullable
    private Lyrics fetchFromHost(String host, TrackInfo track)
            throws IOException, JSONException {
        return fetchFromUrl(buildUrl(host, track));
    }

    /** Look up a candidate by ISRC, then platform id, then title/artist. */
    @Nullable
    private Lyrics fetchCandidateLyrics(JSONObject item)
            throws IOException, JSONException {
        String isrc = item.optString("isrc", "");
        if (!isrc.isEmpty()) {
            Lyrics l = fetchLyricsWithParam("isrc", isrc);
            if (l != null) return l;
        }
        JSONObject ids = item.optJSONObject("id");
        if (ids != null) {
            for (String key : new String[]{"spotify", "qq", "appleMusic", "apple", "deezer"}) {
                String pid = ids.optString(key, "");
                if (!pid.isEmpty()) {
                    Lyrics l = fetchLyricsWithParam("platformId", pid);
                    if (l != null) return l;
                }
            }
        }
        String title = item.optString("title", "");
        String artist = item.optString("artist", "");
        if (title.isEmpty() || artist.isEmpty()) {
            return null;
        }
        long durMs = item.optLong("durationMs", 0);
        TrackInfo candTrack = new TrackInfo(title, artist, "",
                durMs > 0 ? (int) (durMs / 1000) : 0);
        return fetchFromApi(candTrack);
    }

    @Nullable
    private Lyrics fetchLyricsWithParam(String param, String value)
            throws IOException, JSONException {
        for (String host : API_HOSTS) {
            String url = host + "/v1/ttml/get?" + param + "=" + encode(value);
            try {
                Lyrics l = fetchFromUrl(url);
                if (l != null) return l;
            } catch (Exception ex) {
                Logger.printDebug(() -> "LyricsPlus candidate fetch failed: " + host, ex);
            }
        }
        return null;
    }

    @Nullable
    private JSONArray searchCandidates(TrackInfo track) throws IOException, JSONException {
        String q = (track.title() + " " + track.artist()).trim();
        if (q.isEmpty()) {
            return null;
        }
        for (String host : API_HOSTS) {
            JSONArray results = searchCandidatesFromHost(host, q);
            if (results != null && results.length() > 0) {
                return results;
            }
        }
        return null;
    }

    @Nullable
    private JSONArray searchCandidatesFromHost(String host, String q)
            throws IOException, JSONException {
        String url = host + "/v1/songlist/search?q=" + encode(q);
        HttpURLConnection connection = null;
        try {
            connection = LyricsRequests.openConnection(url, Map.of(
                    "User-Agent", BROWSER_USER_AGENT,
                    "Accept", "application/json"));
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(8000);
            if (connection.getResponseCode() != Requester.HTTP_STATUS_CODE_SUCCESS) {
                return null;
            }
            JSONObject root = Requester.parseJSONObject(connection);
            return root != null ? root.optJSONArray("results") : null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    @Nullable
    private Lyrics fetchFromUrl(String url) throws IOException, JSONException {
        HttpURLConnection connection = null;
        try {
            connection = LyricsRequests.openConnection(url, Map.of(
                    "User-Agent", BROWSER_USER_AGENT,
                    "Accept", "application/json"));
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(8000);
            final int code = connection.getResponseCode();
            if (code != Requester.HTTP_STATUS_CODE_SUCCESS) {
                return null;
            }
            JSONObject root = Requester.parseJSONObject(connection);
            if (root == null) {
                return null;
            }
            String ttml = root.optString("ttml", "");
            if (ttml.isEmpty()) {
                // Older mirrors may only answer the v2 JSON shape.
                return parseLyricsJson(root);
            }

            JSONObject timing = root.optJSONObject("processingTime");
            JSONObject selected = timing != null ? timing.optJSONObject("selectedSongMetadata") : null;
            String sourceName = selected != null ? selected.optString("source", "") : "";

            String providerName = name();
            if (!sourceName.isEmpty() && !isLyricsPlusSelfSource(sourceName)) {
                providerName = name() + " (via " + sourceName + ")";
            }
            return TTMLParser.ttmlToLyrics(ttml, providerName, null);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    static String buildUrl(String host, TrackInfo track) throws UnsupportedEncodingException {
        String base = host.endsWith("/") ? host.substring(0, host.length() - 1) : host;
        StringBuilder url = new StringBuilder(base).append("/v1/ttml/get");
        url.append("?title=").append(encode(track.title()));
        url.append("&artist=").append(encode(track.artist()));
        if (!track.album().isEmpty()) {
            url.append("&album=").append(encode(track.album()));
        }
        if (track.durationSeconds() > 0) {
            url.append("&duration=").append(track.durationSeconds());
        }
        return url.toString();
    }

    /** A space is encoded as {@code %20}, never the query-string {@code +}. */
    private static String encode(String value) throws UnsupportedEncodingException {
        return LyricsRequests.encode(value).replace("+", "%20");
    }

    @Nullable
    private Lyrics parseLyricsJson(JSONObject root) {
        if (root == null) {
            return null;
        }
        JSONArray lyricsArray = root.optJSONArray("lyrics");
        if (lyricsArray == null || lyricsArray.length() == 0) {
            return null;
        }

        JSONObject metadata = root.optJSONObject("metadata");
        String upstreamSource = metadata != null ? metadata.optString("source", "") : "";
        if (upstreamSource != null && upstreamSource.isEmpty()) {
            upstreamSource = null;
        }
        if (isLyricsPlusSelfSource(upstreamSource)) {
            upstreamSource = null;
        }
        List<String> songwriters = null;
        JSONArray songPartsJson = null;
        if (metadata != null) {
            JSONArray sw = metadata.optJSONArray("songWriters");
            if (sw != null && sw.length() > 0) {
                songwriters = new ArrayList<>();
                for (int i = 0; i < sw.length(); i++) {
                    String writer = sw.optString(i);
                    if (!writer.isEmpty()) {
                        songwriters.add(writer);
                    }
                }
            }
            songPartsJson = metadata.optJSONArray("songParts");
        }

        List<LyricsLine> lines = new ArrayList<>();
        List<LyricsLine> romanization = new ArrayList<>();
        Map<String, List<LyricsLine>> translations = new HashMap<>();
        boolean hasAnyWordSync = false;

        for (int i = 0; i < lyricsArray.length(); i++) {
            JSONObject item = lyricsArray.optJSONObject(i);
            if (item == null) continue;

            long lineTime = item.optLong("time", LyricsLine.NO_TIME);
            long lineDuration = item.optLong("duration", 0);
            long lineEndTime = (lineTime != LyricsLine.NO_TIME && lineDuration > 0)
                    ? lineTime + lineDuration : LyricsLine.NO_TIME;
            String rawLineText = item.optString("text", "");

            JSONObject element = item.optJSONObject("element");
            String agentId = null;
            String songPart = null;
            if (element != null) {
                String singer = element.optString("singer");
                if (!singer.isEmpty()) agentId = singer;
                int songPartIndex = element.optInt("songPartIndex", -1);
                if (songPartsJson != null && songPartIndex >= 0 && songPartIndex < songPartsJson.length()) {
                    JSONObject sp = songPartsJson.optJSONObject(songPartIndex);
                    if (sp != null) {
                        String name = sp.has("name") ? sp.optString("name") : null;
                        if (name != null && !name.isEmpty()) songPart = name;
                    }
                }
            }
            boolean isDuet = isAgentDuet(agentId);

            JSONArray syllabus = item.optJSONArray("syllabus");
            JSONObject transliteration = item.optJSONObject("transliteration");
            String transText = item.has("translation") ? item.optString("translation") : null;

            JSONArray transSyls = transliteration != null ? transliteration.optJSONArray("syllabus") : null;
            String transliterationText = transliteration != null && transliteration.has("text") ? transliteration.optString("text") : null;

            if (syllabus != null && syllabus.length() > 0) {
                List<WordCandidate> mainSyls = new ArrayList<>();
                List<WordCandidate> bgSyls = new ArrayList<>();
                for (int s = 0; s < syllabus.length(); s++) {
                    JSONObject syl = syllabus.optJSONObject(s);
                    if (syl == null) continue;
                    String roma = null;
                    if (transSyls != null && s < transSyls.length()) {
                        JSONObject tSyl = transSyls.optJSONObject(s);
                        if (tSyl != null) {
                            roma = tSyl.has("text") ? tSyl.optString("text") : null;
                            if (roma != null && roma.trim().isEmpty()) roma = null;
                        }
                    }
                    boolean isBg = syl.optBoolean("isBackground", false);
                    WordCandidate cand = new WordCandidate(syl, roma);
                    if (isBg) {
                        bgSyls.add(cand);
                    } else {
                        mainSyls.add(cand);
                    }
                }

                LyricsLine mainLine = buildLineFromCandidates(
                        mainSyls, lineTime, lineEndTime, lineDuration, rawLineText, agentId, isDuet, false, songPart);
                if (mainLine != null) {
                    lines.add(mainLine);
                    if (!mainLine.words().isEmpty()) hasAnyWordSync = true;
                }

                LyricsLine bgLine = buildLineFromCandidates(
                        bgSyls, lineTime, lineEndTime, lineDuration, null, agentId, isDuet, true, songPart);
                if (bgLine != null) {
                    lines.add(bgLine);
                    if (!bgLine.words().isEmpty()) hasAnyWordSync = true;
                }
            } else {
                String text = rawLineText.trim();
                boolean isBg = false;
                if ((text.startsWith("(") && text.endsWith(")")) || (text.startsWith("（") && text.endsWith("）"))) {
                    isBg = true;
                    text = text.substring(1, text.length() - 1).trim();
                }
                if (!text.isEmpty()) {
                    lines.add(new LyricsLine(lineTime, lineEndTime, text, List.of(), agentId, isDuet, isBg, songPart));
                }
            }

            if (transliterationText != null && !transliterationText.trim().isEmpty()) {
                romanization.add(new LyricsLine(LyricsLine.NO_TIME, transliterationText.trim()));
            }
            if (transText != null && !transText.trim().isEmpty()) {
                translations.computeIfAbsent("default", k -> new ArrayList<>())
                        .add(new LyricsLine(LyricsLine.NO_TIME, transText.trim()));
            }
        }

        if (lines.isEmpty()) {
            return null;
        }

        boolean isSynced = lines.get(0).startTimeMs() != LyricsLine.NO_TIME;
        final String providerName = upstreamSource != null
                ? name() + " (via " + upstreamSource + ")" : name();
        return new Lyrics(lines, providerName, isSynced,
                romanization.isEmpty() ? null : romanization,
                translations.isEmpty() ? null : translations,
                null,
                songwriters,
                null,
                hasAnyWordSync ? "syllable" : "line",
                null);
    }

    private static boolean isLyricsPlusSelfSource(@Nullable String source) {
        if (source == null) return false;
        String normalized = source.toLowerCase(java.util.Locale.ROOT);
        if (normalized.startsWith("q")) {
            normalized = normalized.substring(1);
        }
        return "lyricsplus".equals(normalized);
    }

    private record WordCandidate(JSONObject syl, @Nullable String romaji) {
    }
}
