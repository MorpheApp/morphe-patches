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

public final class BinimumProvider implements LyricsProvider {

    private static final String API_URL = "https://lyricsplus.binimum.org/v2/lyrics/get";
    private static final String LEGACY_BASE_URL = "https://lyrics-api.binimum.org/";

    private static final String BROWSER_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    @Override
    public String name() {
        return "BiniLyrics";
    }

    @Nullable
    @Override
    public FetchResult fetch(TrackInfo track) throws Exception {
        if (track.title().isEmpty() || track.artist().isEmpty()) {
            return null;
        }

        // The v2 API is asked first, for the sources it merges.
        try {
            Lyrics lyrics = fetchFromApi(track);
            if (lyrics != null && !lyrics.lines().isEmpty()) {
                return FetchResult.of(lyrics, track);
            }
        } catch (Exception ex) {
            Logger.printDebug(() -> "BiniLyrics v2 fetch failed", ex);
        }

        // A title with a video tag is asked again without it.
        final String cleanTitle = track.title()
                .replace("(Official Video)", "")
                .replace("(Official Music Video)", "")
                .replace("(Official Audio)", "")
                .trim();
        if (!cleanTitle.equals(track.title())) {
            TrackInfo cleanTrack = new TrackInfo(cleanTitle, track.artist(), track.album(), track.durationSeconds());
            try {
                Lyrics lyrics = fetchFromApi(cleanTrack);
                if (lyrics != null && !lyrics.lines().isEmpty()) {
                    return FetchResult.of(lyrics, cleanTrack);
                }
            } catch (Exception ex) {
                Logger.printDebug(() -> "BiniLyrics v2 fetch failed for the cleaned title", ex);
            }
        }

        // The legacy API is the last resort.
        try {
            String lyricsUrl = resolveLyricsUrl(track);
            if (lyricsUrl != null) {
                HttpURLConnection connection = null;
                try {
                    connection = LyricsRequests.openConnection(lyricsUrl);
                    if (connection.getResponseCode() == Requester.HTTP_STATUS_CODE_SUCCESS) {
                        String ttml = Requester.parseString(connection);
                        return FetchResult.blind(TTMLParser.ttmlToLyrics(ttml, name(), lyricsUrl));
                    }
                } finally {
                    if (connection != null) {
                        connection.disconnect();
                    }
                }
            }
        } catch (Exception ex) {
            Logger.printDebug(() -> "Legacy Binimum LRCRED fetch failed", ex);
        }

        return null;
    }

    @Nullable
    private Lyrics fetchFromApi(TrackInfo track) throws IOException, JSONException {
        StringBuilder url = new StringBuilder(API_URL);
        url.append("?title=").append(LyricsRequests.encode(track.title()));
        url.append("&artist=").append(LyricsRequests.encode(track.artist()));
        if (!track.album().isEmpty()) {
            url.append("&album=").append(LyricsRequests.encode(track.album()));
        }
        if (track.durationSeconds() > 0) {
            url.append("&duration=").append(track.durationSeconds());
        }
        url.append("&source=apple,lyricsplus,qq");

        HttpURLConnection connection = null;
        try {
            connection = LyricsRequests.openConnection(url.toString());
            connection.setRequestProperty("User-Agent", BROWSER_USER_AGENT);
            connection.setRequestProperty("Accept", "application/json");
            final int code = connection.getResponseCode();
            if (code != Requester.HTTP_STATUS_CODE_SUCCESS) {
                return null;
            }
            JSONObject root = Requester.parseJSONObject(connection);
            return parseLyricsJson(root, url.toString());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    @Nullable
    private Lyrics parseLyricsJson(JSONObject root, String sourceUrl) {
        if (root == null) {
            return null;
        }
        JSONArray lyricsArray = root.optJSONArray("lyrics");
        if (lyricsArray == null || lyricsArray.length() == 0) {
            return null;
        }

        JSONObject metadata = root.optJSONObject("metadata");
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
                        String name = sp.optString("name", null);
                        if (name != null && !name.isEmpty()) songPart = name;
                    }
                }
            }
            boolean isDuet = isAgentDuet(agentId);

            JSONArray syllabus = item.optJSONArray("syllabus");
            JSONObject transliteration = item.optJSONObject("transliteration");
            String transText = item.optString("translation", null);

            JSONArray transSyls = transliteration != null ? transliteration.optJSONArray("syllabus") : null;
            String transliterationText = transliteration != null ? transliteration.optString("text", null) : null;

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
                            roma = tSyl.optString("text", null);
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
                // Line synced only, with no word timing.
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

            // The transliteration and translation of the line, if any.
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
        return new Lyrics(lines, name(), isSynced,
                romanization.isEmpty() ? null : romanization,
                translations.isEmpty() ? null : translations,
                null,
                songwriters,
                null,
                hasAnyWordSync ? "syllable" : "line",
                sourceUrl);
    }

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

        // Words with no spaces between them in a spaced script are joined with spaces.
        if (fullText.indexOf(' ') < 0 && words.size() > 1 && !TTMLParser.isContinuousCjk(words.get(0).text())) {
            StringBuilder reconstructed = new StringBuilder();
            for (int i = 0; i < words.size(); i++) {
                Word word = words.get(i);
                if (i > 0) {
                    reconstructed.append(' ');
                }
                reconstructed.append(word.text());
                boolean hasSpace = (i < words.size() - 1);
                words.set(i, new Word(word.startMs(), word.endMs(), word.text(), word.romaji(), hasSpace));
            }
            fullText = reconstructed.toString();
        }

        // The last word ends the line, so it has no space after it.
        if (!words.isEmpty()) {
            Word lastWord = words.get(words.size() - 1);
            if (lastWord.endsWithSpace()) {
                words.set(words.size() - 1, new Word(lastWord.startMs(), lastWord.endMs(), lastWord.text(), lastWord.romaji(), false));
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

    private static class WordCandidate {
        final JSONObject syl;
        final @Nullable String romaji;

        WordCandidate(JSONObject syl, @Nullable String romaji) {
            this.syl = syl;
            this.romaji = romaji;
        }
    }

    @Nullable
    private String resolveLyricsUrl(TrackInfo track) throws IOException, JSONException {
        if (track.title().isEmpty() || track.artist().isEmpty()) {
            return null;
        }

        StringBuilder url = new StringBuilder(LEGACY_BASE_URL);
        url.append("?track=").append(LyricsRequests.encode(track.title()));
        url.append("&artist=").append(LyricsRequests.encode(track.artist()));
        if (!track.album().isEmpty()) {
            url.append("&album=").append(LyricsRequests.encode(track.album()));
        }
        if (track.durationSeconds() > 0) {
            url.append("&duration=").append(track.durationSeconds());
        }

        HttpURLConnection connection = null;
        try {
            connection = LyricsRequests.openConnection(url.toString());
            final int responseCode = connection.getResponseCode();
            if (responseCode == 404) {
                connection.disconnect();
                return null;
            }
            if (responseCode != 200) {
                LyricsRequests.logFailure(name(), connection);
                return null;
            }
            JSONObject response = Requester.parseJSONObject(connection);
            JSONArray results = response.optJSONArray("results");
            if (results == null || results.length() == 0) {
                return null;
            }
            JSONObject best = results.optJSONObject(0);
            if (best == null) {
                return null;
            }
            return LyricsRequests.optString(best, "lyricsUrl");
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
}
