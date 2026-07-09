/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.voiceovertranslation;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.innertube.utils.AuthUtils;
import app.morphe.extension.shared.requests.Requester;
import app.morphe.extension.youtube.patches.CaptionCookiesPatch;

/**
 * Pulls the video's caption track (Innertube preferred, direct timedtext as fallback),
 * parses the JSON3 payload into line-level segments, merges those into sentence-sized
 * chunks suitable for TTS, and delegates translation to {@link TranscriptTranslator}
 * when the source language differs from the user's target.
 */
final class TranscriptFetcher {

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 15_000;
    private static final String INNERTUBE_PLAYER_URL =
            "https://www.youtube.com/youtubei/v1/player?prettyPrint=false";

    /** Language code detected from the video's own caption track. Updated on every successful fetch. */
    static volatile String lastSourceLang = "en";

    /**
     * Fetches and, if needed, translates the transcript. The returned list is immediately
     * usable; when translation spans multiple batches, later batches are published
     * asynchronously through {@code onUpdate} (see {@link TranscriptTranslator#translate}).
     * {@code cancelled} is polled before each background batch so translation of an
     * abandoned video stops early.
     */
    static List<TranscriptSegment> fetch(String videoId, Consumer<List<TranscriptSegment>> onUpdate,
                                         BooleanSupplier cancelled) {
        List<TranscriptSegment> segments = fetchEnglishSegments(videoId);

        if (!segments.isEmpty()) {
            String targetLangCode = VoiceOverTranslationPatch.resolveTargetLang();
            if (isSpokenLanguageDifferent(targetLangCode, lastSourceLang)) {
                segments = TranscriptTranslator.translate(videoId, segments, targetLangCode, onUpdate, cancelled);
            }
        }

        return segments;
    }

    /**
     * @return true if {@code source} needs translation to reach {@code target}. Portuguese
     * is region-sensitive (pt-BR != pt-PT); other languages match on the base subtag.
     */
    public static boolean isSpokenLanguageDifferent(String target, String source) {
        if (target == null || source == null) return true;
        if (target.equalsIgnoreCase(source)) return false;
        if (target.startsWith("pt-")) return true;
        return !VoiceCatalog.getIso639(target).equalsIgnoreCase(VoiceCatalog.getIso639(source));
    }

    private static List<TranscriptSegment> fetchEnglishSegments(String videoId) {
        String captionUrl = null;
        String poToken    = null;
        try {
            String[] innertubeResult = fetchFromInnertube(videoId);
            captionUrl = innertubeResult[0];
            poToken    = innertubeResult[1];
        } catch (Exception ex) {
            Logger.printDebug(() -> "Innertube player failed", ex);
        }

        if (captionUrl != null) {
            try {
                String baseUrl = captionUrl.replaceAll("&fmt=[^&]*", "") + "&fmt=json3";
                if (poToken != null) baseUrl += "&pot=" + poToken;

                String json = fetchUrl(baseUrl);
                String detectedLang = extractLangFromUrl(captionUrl).split("-")[0];
                List<TranscriptSegment> segments = parseJson3(json, detectedLang);
                if (!segments.isEmpty()) {
                    lastSourceLang = detectedLang;
                    return segments;
                }
            } catch (Exception ex) {
                Logger.printDebug(() -> "Caption fetch failed, trying direct", ex);
            }
        }

        return fetchDirect(videoId);
    }

    private static String[] fetchFromInnertube(String videoId) throws Exception {
        Utils.verifyOffMainThread();

        String body = "{\"context\":{\"client\":{\"clientName\":\"ANDROID\","
                + "\"clientVersion\":\"20.10.38\"}},"
                + "\"videoId\":\"" + videoId + "\"}";

        //noinspection ExtractMethodRecommender
        HttpURLConnection conn = (HttpURLConnection) new URL(INNERTUBE_PLAYER_URL).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("User-Agent",
                "com.google.android.youtube/20.10.38 (Linux; U; Android 11) gzip");
        conn.setRequestProperty("X-YouTube-Client-Name", "3");
        conn.setRequestProperty("X-YouTube-Client-Version", "20.10.38");
        conn.setDoOutput(true);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }

        final int code = conn.getResponseCode();
        if (code != 200) throw new Exception("Unexpected response status: " + code);

        String response = Requester.parseString(conn);
        return new String[]{findBestCaptionUrl(response), extractPoToken(response)};
    }

    @Nullable
    private static String extractPoToken(String json) {
        int idx = json.indexOf("\"poToken\":\"");
        if (idx < 0) return null;
        idx += "\"poToken\":\"".length();
        final int end = json.indexOf('"', idx);
        return end < 0 ? null : json.substring(idx, end);
    }

    /**
     * Returns the best caption URL from the captionTracks list.
     * Prefers a non-gemini track in the target language; falls back to a non-gemini
     * English track, then the first non-gemini track, then the first available.
     */
    @Nullable
    private static String findBestCaptionUrl(String json) {
        final int tracksIdx = json.indexOf("\"captionTracks\":[");
        if (tracksIdx < 0) return null;

        String targetLang = VoiceCatalog.getIso639(VoiceOverTranslationPatch.resolveTargetLang());
        String firstUrl = null;
        String firstNonGemini = null;
        String targetLangUrl = null;
        String englishUrl = null;
        int searchFrom = tracksIdx;

        while (true) {
            int baseUrlIdx = json.indexOf("\"baseUrl\":\"", searchFrom);
            if (baseUrlIdx < 0 || baseUrlIdx > tracksIdx + 50_000) break;
            baseUrlIdx += "\"baseUrl\":\"".length();

            final int endIdx = json.indexOf('"', baseUrlIdx);
            if (endIdx < 0) break;

            String url = json.substring(baseUrlIdx, endIdx)
                    .replace("\\u0026", "&")
                    .replace("\\u003d", "=")
                    .replace("\\u003e", ">")
                    .replace("\\u003c", "<");

            if (firstUrl == null) firstUrl = url;
            final boolean nonGemini = !url.contains("variant=gemini");
            if (firstNonGemini == null && nonGemini) firstNonGemini = url;

            String urlLang = extractLangFromUrl(url).split("-")[0];
            if (targetLangUrl == null && nonGemini && targetLang.equals(urlLang)) targetLangUrl = url;
            if (englishUrl == null && nonGemini && "en".equals(urlLang)) englishUrl = url;

            searchFrom = endIdx + 1;
        }

        if (targetLangUrl != null) return targetLangUrl;
        if (englishUrl != null) return englishUrl;
        return firstNonGemini != null ? firstNonGemini : firstUrl;
    }

    private static List<TranscriptSegment> fetchDirect(String videoId) {
        for (String srcLang : List.of("en", "en-US", "en-GB")) {
            try {
                String urlStr = "https://www.youtube.com/api/timedtext?v=" + videoId
                        + "&lang=" + srcLang + "&kind=asr&fmt=json3";
                String json = fetchUrl(urlStr);
                if (!json.isEmpty()) {
                    List<TranscriptSegment> segments = parseJson3(json, "en");
                    if (!segments.isEmpty()) {
                        lastSourceLang = "en";
                        return segments;
                    }
                }
            } catch (Exception ex) {
                final String langFinal = srcLang;
                Logger.printDebug(() -> "Direct caption fetch failed lang: " + langFinal, ex);
            }
        }
        Logger.printDebug(() -> "No captions available for video: " + videoId);
        return new ArrayList<>();
    }

    private static String extractLangFromUrl(String url) {
        for (String prefix : List.of("&lang=", "?lang=")) {
            int idx = url.indexOf(prefix);
            if (idx >= 0) {
                idx += prefix.length();
                final int end = url.indexOf('&', idx);
                return end < 0 ? url.substring(idx) : url.substring(idx, end);
            }
        }
        return "en";
    }

    // Flush a merged sentence when it grows past this size, to keep TTS utterances manageable.
    private static final int MAX_SENTENCE_CHARS = 300;
    // A silence gap longer than this between lines starts a new utterance even mid-sentence.
    private static final long MAX_SENTENCE_GAP_MS = 1_500;
    // Small gaps between segments are closed when they are below this threshold.
    private static final long CLOSE_GAP_THRESHOLD_MS = 2_500;
    // Minimum duration for a merged segment to avoid being skipped by the playback polling.
    private static final long MIN_SEGMENT_DURATION_MS = 2_000;

    private static final Pattern BRACKETS_PATTERN = Pattern.compile("\\[[^]]*]");
    private static final Pattern PARENTHESES_PATTERN = Pattern.compile("\\([^)]*\\)");

    // Heuristics for old ASR tracks that have no punctuation at all.
    // A pause this long is treated as a sentence boundary on its own.
    private static final long UNPUNCTUATED_GAP_MS = 700;
    // A shorter pause counts as a boundary only when the next line starts with a capital.
    private static final long UNPUNCTUATED_SOFT_GAP_MS = 250;
    // Tighter length cap so unpunctuated chunks stay short and re-sync with the video often.
    private static final int MAX_UNPUNCTUATED_CHARS = 200;

    private static List<TranscriptSegment> parseJson3(String json, String sourceLang) throws Exception {
        List<TranscriptSegment> lines = new ArrayList<>();
        JSONObject root = new JSONObject(json);
        JSONArray events = root.optJSONArray("events");
        if (events == null) return lines;

        for (int i = 0, eventsLength = events.length(); i < eventsLength; i++) {
            JSONObject event = events.getJSONObject(i);
            // ASR streams emit append events that only carry a "\n" to scroll the
            // 2-line caption window. They duplicate timing of real lines - skip them.
            if (event.optInt("aAppend", 0) == 1) continue;

            JSONArray segs = event.optJSONArray("segs");
            if (segs == null) continue;

            final long startMs = event.optLong("tStartMs", -1);
            if (startMs < 0) continue;
            final long durationMs = event.optLong("dDurationMs", 2000);

            StringBuilder text = new StringBuilder();
            for (int j = 0, segsLength = segs.length(); j < segsLength; j++) {
                text.append(segs.getJSONObject(j).optString("utf8", ""));
            }

            //noinspection ExtractMethodRecommender
            String textStr = text.toString()
                    .replace('\n', ' ')
                    .replace(">>", "")
                    .replace("♪", "")
                    .replace("♫", "")
                    .trim();

            // Drop sound effect markers such as [Applause] or (music) - they should not be spoken.
            textStr = BRACKETS_PATTERN.matcher(textStr).replaceAll("");
            textStr = PARENTHESES_PATTERN.matcher(textStr).replaceAll("");
            textStr = textStr.trim();

            if (!textStr.isEmpty()) {
                lines.add(new TranscriptSegment(startMs,
                        startMs + durationMs, textStr, sourceLang));
            }
        }

        // dDurationMs is display time: with ASR a line stays on screen while the next
        // line is already spoken, so ranges overlap. Clamp each end to the next start
        // so segments represent actual speech time instead of caption visibility.
        // Small gaps are closed so TTS flows without pauses between segments.
        for (int i = 0, last = lines.size() - 1; i < last; i++) {
            TranscriptSegment cur = lines.get(i);
            TranscriptSegment next = lines.get(i + 1);

            final long curEndMs = cur.endMs;
            final long nextStartMs = next.startMs;
            if (curEndMs > nextStartMs) {
                lines.set(i, new TranscriptSegment(cur.startMs, nextStartMs, cur.text, cur.lang));
            } else if (nextStartMs - curEndMs <= CLOSE_GAP_THRESHOLD_MS) {
                final long mid = (curEndMs + nextStartMs) / 2;
                lines.set(i, new TranscriptSegment(cur.startMs, mid, cur.text, cur.lang));
                lines.set(i + 1, new TranscriptSegment(mid, next.endMs, next.text, next.lang));
            }
        }

        return mergeIntoSentences(lines);
    }

    /**
     * Merges caption lines into sentence-sized segments so TTS speaks whole sentences
     * without pauses at line breaks. For punctuated transcripts a sentence ends on
     * terminal punctuation; old ASR tracks have no punctuation at all, so boundaries
     * are approximated from speech pauses and capitalization instead.
     */
    private static List<TranscriptSegment> mergeIntoSentences(List<TranscriptSegment> lines) {
        final boolean punctuated = detectPunctuation(lines);
        List<TranscriptSegment> sentences = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        long startMs = 0;
        long endMs;
        String sentenceLang = "";

        for (int i = 0, size = lines.size(); i < size; i++) {
            TranscriptSegment line = lines.get(i);
            if (text.length() == 0) {
                startMs = line.startMs;
                sentenceLang = line.lang;
            } else {
                text.append(' ');
            }
            text.append(line.text);
            endMs = line.endMs;

            boolean flush;
            if (i == size - 1) {
                flush = true;
            } else {
                final long gap = lines.get(i + 1).startMs - endMs;
                if (punctuated) {
                    flush = endsSentence(text)
                            || text.length() >= MAX_SENTENCE_CHARS
                            || (gap > MAX_SENTENCE_GAP_MS && text.length() > 80);
                } else {
                    flush = gap > UNPUNCTUATED_GAP_MS
                            || (gap > UNPUNCTUATED_SOFT_GAP_MS
                            && startsWithUpperCase(lines.get(i + 1).text))
                            || text.length() >= MAX_UNPUNCTUATED_CHARS;
                }

                if (flush && endMs - startMs < MIN_SEGMENT_DURATION_MS && gap <= 0) {
                    flush = false;
                }
            }

            if (flush) {
                String fullText = text.toString();
                if (i < size - 1) {
                    final int lastEnd = findLastSentenceEnd(fullText);
                    final int fullTextLength = fullText.length();
                    if (lastEnd != -1 && lastEnd < fullTextLength - 1) {
                        String head = fullText.substring(0, lastEnd + 1).trim();
                        String tail = fullText.substring(lastEnd + 1).trim();

                        final long splitMs = startMs + (long) ((endMs - startMs)
                                * ((double) head.length() / fullTextLength));
                        sentences.add(new TranscriptSegment(startMs, splitMs, head, sentenceLang));

                        text.setLength(0);
                        text.append(tail);
                        startMs = splitMs;
                        continue;
                    }
                }
                sentences.add(new TranscriptSegment(startMs, endMs, fullText, sentenceLang));
                text.setLength(0);
            }
        }
        return sentences;
    }

    /**
     * Returns true when a meaningful share of lines contains terminal punctuation.
     * Old auto-generated tracks contain none, so punctuation can't be trusted there
     * as a sentence boundary signal.
     */
    private static boolean detectPunctuation(List<TranscriptSegment> lines) {
        int punctuatedLines = 0;
        for (TranscriptSegment line : lines) {
            String t = line.text;
            for (int i = 0, len = t.length(); i < len; i++) {
                final char c = t.charAt(i);
                if (c == '.' || c == '!' || c == '?') {
                    punctuatedLines++;
                    break;
                }
            }
        }
        // At least ~10% of lines must carry punctuation - the occasional "$5.99"
        // in an otherwise unpunctuated track should not flip the mode.
        return punctuatedLines * 10 >= lines.size();
    }

    private static boolean startsWithUpperCase(String text) {
        for (int i = 0, len = text.length(); i < len; i++) {
            final char c = text.charAt(i);
            if (Character.isLetter(c)) return Character.isUpperCase(c);
        }
        return false;
    }

    private static boolean endsSentence(CharSequence text) {
        if (text.length() == 0) return false;
        final char c = text.charAt(text.length() - 1);
        if (c != '.' && c != '!' && c != '?' && c != '…') return false;

        // Check for common abbreviations that end with a period but don't end a sentence.
        if (c == '.') {
            final String s = text.toString();
            if (s.endsWith("Mr.") || s.endsWith("Ms.") || s.endsWith("Dr.") || s.endsWith("St.")
                    || s.endsWith("Inc.") || s.endsWith("Ltd.") || s.endsWith("Jr.")
                    || s.endsWith("Sr.") || s.endsWith("vs.")) {
                return false;
            }
            // Heuristic for initials: "A.", "B.", etc.
            final int sLength = s.length();
            return sLength < 3 || s.charAt(sLength - 2) != ' '
                    || !Character.isUpperCase(s.charAt(sLength - 3));
        }
        return true;
    }

    private static int findLastSentenceEnd(String text) {
        for (int i = text.length() - 1; i >= 0; i--) {
            char c = text.charAt(i);
            if (c == '.' || c == '!' || c == '?' || c == '…') {
                if (endsSentence(text.substring(0, i + 1))) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static String fetchUrl(String urlStr) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setRequestProperty("User-Agent", CaptionCookiesPatch.getUserAgent());
        String cookies = CaptionCookiesPatch.getCookies();
        if (!cookies.isEmpty()) {
            conn.setRequestProperty("Cookie", cookies);
        }
        Map<String, String> authHeaders = AuthUtils.getRequestHeader();
        for (Map.Entry<String, String> entry : authHeaders.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                conn.setRequestProperty(entry.getKey(), entry.getValue());
            }
        }

        final int responseCode = conn.getResponseCode();
        if (responseCode != 200) {
            throw new Exception("HTTP response code: " + responseCode + " url: " + urlStr);
        }
        return Requester.parseString(conn);
    }
}
