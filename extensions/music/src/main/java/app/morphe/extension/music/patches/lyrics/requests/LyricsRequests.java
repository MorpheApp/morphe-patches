/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2269
 * https://github.com/MorpheApp/morphe-patches/pull/2625
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.requests;

import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPInputStream;

import app.morphe.extension.music.patches.lyrics.Lyrics;
import app.morphe.extension.music.patches.lyrics.LyricsLine;
import app.morphe.extension.music.patches.lyrics.TrackInfo;
import app.morphe.extension.music.patches.lyrics.Word;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.requests.Requester;

/**
 * Shared HTTP helpers for the lyrics providers.
 */
public final class LyricsRequests {

    static final int MAX_CANDIDATES = 5;
    private static final int CONNECT_TIMEOUT_MILLISECONDS = 5 * 1000;
    private static final int READ_TIMEOUT_MILLISECONDS = 5 * 1000;

    private LyricsRequests() {
    }

    static String userAgent() {
        return "Morphe/" + Utils.getAppVersionName()
                + " (" + Utils.getPatchesReleaseVersion() + ")"
                + " https://github.com/MorpheApp/morphe-patches";
    }

    public static String deviceLanguage() {
        return Locale.getDefault().getLanguage();
    }

    public static long fractionToMs(String fraction) {
        if (fraction == null || fraction.isEmpty()) {
            throw new NumberFormatException("Empty LRC fraction");
        }
        if (fraction.length() == 1) {
            return Long.parseLong(fraction) * 100L;
        }
        if (fraction.length() == 2) {
            return Long.parseLong(fraction) * 10L;
        }
        return Long.parseLong(fraction.substring(0, 3));
    }

    public static List<LyricsLine> applyOffset(List<LyricsLine> lines, long offsetMs) {
        if (lines == null || lines.isEmpty() || offsetMs == 0L) {
            return lines;
        }
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

    /**
     * Opens a GET connection. LRCLIB asks clients to identify themselves in the
     * User-Agent header, and rate limits requests that do not.
     */
    static HttpURLConnection openConnection(String url) throws IOException {
        HttpURLConnection connection = Requester.openConnection(url);
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(CONNECT_TIMEOUT_MILLISECONDS);
        connection.setReadTimeout(READ_TIMEOUT_MILLISECONDS);
        connection.setRequestProperty("User-Agent", userAgent());
        return connection;
    }

    /**
     * Opens a GET connection with configurable timeouts and extra headers.
     */
    static HttpURLConnection openConnection(String url, int connectTimeoutMs,
            int readTimeoutMs, Map<String, String> headers) throws IOException {
        HttpURLConnection connection = Requester.openConnection(url);
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(connectTimeoutMs);
        connection.setReadTimeout(readTimeoutMs);
        connection.setRequestProperty("User-Agent", userAgent());
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                connection.setRequestProperty(entry.getKey(), entry.getValue());
            }
        }
        return connection;
    }

    /**
     * Opens a POST connection with a JSON body. The caller reads the response with
     * one of the {@link app.morphe.extension.shared.requests.Requester} parse helpers.
     */
    static HttpURLConnection postJson(String url, String json) throws IOException {
        return postConnection(url, json, "application/json; charset=utf-8", null);
    }

    static HttpURLConnection postJson(String url, String json, Map<String, String> headers) throws IOException {
        return postConnection(url, json, "application/json; charset=utf-8", headers);
    }

    /**
     * Opens a POST connection with an {@code application/x-www-form-urlencoded} body.
     */
    static HttpURLConnection postForm(String url, String form) throws IOException {
        return postConnection(url, form, "application/x-www-form-urlencoded; charset=utf-8", null);
    }

    /**
     * Like {@link #postForm(String, String)} but with extra request headers, applied
     * before the body is written so they are sent.
     */
    static HttpURLConnection postForm(String url, String form, Map<String, String> headers) throws IOException {
        return postConnection(url, form, "application/x-www-form-urlencoded; charset=utf-8", headers);
    }

    private static HttpURLConnection postConnection(String url, String body, String contentType,
                                                   Map<String, String> headers) throws IOException {
        HttpURLConnection connection = Requester.openConnection(url);
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(CONNECT_TIMEOUT_MILLISECONDS);
        connection.setReadTimeout(READ_TIMEOUT_MILLISECONDS);
        connection.setRequestProperty("User-Agent", userAgent());
        connection.setRequestProperty("Content-Type", contentType);
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                connection.setRequestProperty(entry.getKey(), entry.getValue());
            }
        }
        connection.setDoOutput(true);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream output = connection.getOutputStream()) {
            output.write(bytes);
        }
        return connection;
    }

    static void logFailure(String provider, HttpURLConnection connection) {
        try {
            final int code = connection.getResponseCode();
            String message = connection.getResponseMessage();
            Logger.printDebug(() -> provider + " request failed: " + code + " " + message);
        } catch (IOException ex) {
            Logger.printDebug(() -> provider + " request failed", ex);
        } finally {
            connection.disconnect();
        }
    }

    /**
     * The Charset overload of encode() needs API 33, so the charset is named instead.
     */
    @SuppressWarnings("CharsetObjectCanBeUsed")
    static String encode(String value) throws UnsupportedEncodingException {
        return URLEncoder.encode(value, "UTF-8");
    }

    /** Walks nested objects, returning null as soon as a link of the chain is missing. */
    @Nullable
    static JSONObject optPath(@Nullable JSONObject root, String... keys) {
        JSONObject node = root;
        for (String key : keys) {
            if (node == null) {
                return null;
            }
            node = node.optJSONObject(key);
        }
        return node;
    }

    @Nullable
    static String optString(JSONObject object, String key) {
        if (object.isNull(key)) {
            return null;
        }
        final String value = object.optString(key, "");
        return value.trim().isEmpty() ? null : value;
    }

    static String parseGzipString(HttpURLConnection connection) throws IOException {
        final InputStream raw = connection.getInputStream();
        final InputStream stream = "gzip".equalsIgnoreCase(connection.getContentEncoding())
                ? new GZIPInputStream(raw) : raw;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            final StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        }
    }

    static JSONObject parseGzipJsonObject(HttpURLConnection connection)
            throws org.json.JSONException, IOException {
        return new JSONObject(parseGzipString(connection));
    }

    static List<LyricsLine> parsePlainTextLines(String text) {
        final List<LyricsLine> lines = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return lines;
        }
        for (String line : text.split("\\r?\\n")) {
            final String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                lines.add(new LyricsLine(LyricsLine.NO_TIME, trimmed));
            }
        }
        return lines;
    }

    static void throttle(AtomicLong lastRequestTime, long minIntervalMs) {
        final long now = System.currentTimeMillis();
        final long elapsed = now - lastRequestTime.get();
        if (elapsed < minIntervalMs) {
            try {
                Thread.sleep(minIntervalMs - elapsed);
            } catch (InterruptedException ex) {
                Logger.printDebug(() -> "Interrupted during throttle sleep", ex);
                // Sleeping cleared the flag, so it is restored to keep the cancellation
                // visible to the lookup that is being abandoned.
                Thread.currentThread().interrupt();
            }
        }
        lastRequestTime.set(System.currentTimeMillis());
    }

    public static final int SOFT_MIN = 2;

    public static final int VIDEO_ID_TRUST = 10;

    public static final int NEUTRAL = 6;

    public enum Evidence {
        EQUAL,
        MATCH,
        PARTIAL,
        MISSING,
        MISMATCH,
        NONE
    }

    /**
     * Result of evaluating a candidate's metadata against a prepared track.
     *
     * @param score   0–11 ranking score (title 0–5 + artist 0–3 + duration 0–2 + album 0–1)
     * @param title   title evidence
     * @param artist  artist evidence
     * @param duration duration evidence (MISSING when either side unknown)
     * @param album   album evidence
     */
    public record MatchVerdict(int score, Evidence title, Evidence artist,
                               Evidence duration, Evidence album) {

        public static MatchVerdict videoIdTrust() {
            return new MatchVerdict(VIDEO_ID_TRUST, Evidence.MATCH, Evidence.MISSING,
                    Evidence.MISSING, Evidence.MISSING);
        }
    }

    public static final class PreparedTrack {
        final String title;
        final String artist;
        final String album;
        final int durationSec;
        final boolean titleValid;

        PreparedTrack(TrackInfo track) {
            this.title = normalizeForMatch(track.title());
            this.artist = normalizeForMatch(track.artist());
            this.album = normalizeForMatch(track.album());
            this.durationSec = track.durationSeconds();
            this.titleValid = !this.title.isEmpty();
        }

        public MatchVerdict evaluate(@Nullable String title, @Nullable String artist,
                                     long durationSec, @Nullable String album) {
            final String t = normalizeForMatch(title);
            final String a = normalizeForMatch(artist);
            final String al = normalizeForMatch(album);

            final Evidence titleEv;
            int titleScore;
            if (!titleValid) {
                titleEv = Evidence.MISSING;
                titleScore = 0;
            } else if (t.isEmpty()) {
                titleEv = Evidence.NONE;
                titleScore = 0;
            } else if (t.equals(this.title)) {
                titleEv = Evidence.EQUAL;
                titleScore = 5;
            } else if (containsEither(t, this.title)) {
                titleEv = Evidence.MATCH;
                titleScore = 3;
            } else {
                final double j = jaccard(t, this.title);
                if (j >= 0.6) {
                    titleEv = Evidence.PARTIAL;
                    titleScore = 2;
                } else if (j >= 0.35) {
                    titleEv = Evidence.PARTIAL;
                    titleScore = 1;
                } else {
                    titleEv = Evidence.NONE;
                    titleScore = 0;
                }
            }

            final Evidence artistEv;
            int artistScore;
            if (a.isEmpty()) {
                artistEv = Evidence.MISSING;
                artistScore = 0;
            } else if (this.artist.isEmpty()) {
                artistEv = Evidence.MISSING;
                artistScore = 0;
            } else if (a.equals(this.artist)) {
                artistEv = Evidence.MATCH;
                artistScore = 3;
            } else if (containsEither(a, this.artist)) {
                artistEv = Evidence.MATCH;
                artistScore = 3;
            } else if (splitArtistHits(a, this.artist)) {
                artistEv = Evidence.PARTIAL;
                artistScore = 1;
            } else {
                artistEv = Evidence.MISMATCH;
                artistScore = 0;
            }

            final Evidence durationEv;
            int durationScore;
            if (durationSec <= 0 || this.durationSec <= 0) {
                durationEv = Evidence.MISSING;
                durationScore = 0;
            } else {
                final long delta = Math.abs(durationSec - this.durationSec);
                if (delta > 15) {
                    durationEv = Evidence.MISMATCH;
                    durationScore = 0;
                } else if (delta <= 2) {
                    durationEv = Evidence.MATCH;
                    durationScore = 2;
                } else if (delta <= 5) {
                    durationEv = Evidence.PARTIAL;
                    durationScore = 1;
                } else {
                    durationEv = Evidence.PARTIAL;
                    durationScore = 0;
                }
            }

            final Evidence albumEv;
            int albumScore;
            if (al.isEmpty() || this.album.isEmpty()) {
                albumEv = Evidence.MISSING;
                albumScore = 0;
            } else if (al.equals(this.album) || containsEither(al, this.album)) {
                albumEv = Evidence.MATCH;
                albumScore = 1;
            } else {
                albumEv = Evidence.MISMATCH;
                albumScore = 0;
            }

            return new MatchVerdict(titleScore + artistScore + durationScore + albumScore,
                    titleEv, artistEv, durationEv, albumEv);
        }
    }

    public static PreparedTrack prepare(TrackInfo track) {
        return new PreparedTrack(track);
    }

    /**
     * Normalizes a string for matching: NFKC, lowercase, decoration strip,
     * fullwidth punctuation fold and whitespace collapse. Semantic decorations
     * (years, part numbers, live/acoustic/feat, …) are kept so that distinct
     * versions do not collapse into a false EQUAL.
     */
    public static String normalizeForMatch(@Nullable String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            return "";
        }
        s = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFKC);
        s = s.toLowerCase(Locale.ROOT);
        s = stripDecorations(s);
        s = foldPunctuation(s);
        s = s.replaceAll("\\s+", " ").trim();
        return s;
    }

    private static volatile String decorationRegexSource;
    private static volatile java.util.regex.Pattern decorationPattern;

    private static String stripDecorations(String s) {
        String regex = Settings.LYRICS_CUSTOM_REGEX.get();
        if (regex == null || regex.trim().isEmpty()) {
            return s;
        }
        java.util.regex.Pattern pattern = decorationPattern;
        if (pattern == null || !regex.equals(decorationRegexSource)) {
            try {
                pattern = java.util.regex.Pattern.compile(regex);
            } catch (Exception ex) {
                Logger.printDebug(() -> "Invalid lyrics decoration regex", ex);
                return s;
            }
            decorationPattern = pattern;
            decorationRegexSource = regex;
        }
        return pattern.matcher(s).replaceAll("");
    }

    private static String foldPunctuation(String s) {
        final StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0xFF01 && c <= 0xFF5E) {
                c = (char) (c - 0xFEE0);
            }
            if (c == '·' || c == '•' || c == '・') {
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    public static boolean containsEither(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) {
            return false;
        }
        if (a.contains(b) || b.contains(a)) {
            final String shorter = a.length() <= b.length() ? a : b;
            final String longer = a.length() <= b.length() ? b : a;
            if (shorter.length() < 4 && !isCjk(shorter.charAt(0))) {
                return longer.length() <= shorter.length() + 6;
            }
            if (shorter.length() < 2 && isCjk(shorter.charAt(0))) {
                return longer.length() <= shorter.length() + 4;
            }
            return true;
        }
        return false;
    }

    private static boolean splitArtistHits(String candidateArtist, String queryArtist) {
        final String[] parts = candidateArtist.split("\\s*(?:和|&|feat\\.?|ft\\.?|,|/|×)\\s*");
        for (String part : parts) {
            final String p = part.trim();
            if (p.isEmpty()) {
                continue;
            }
            if (p.equals(queryArtist)) {
                return true;
            }
            if (p.length() >= 2 || queryArtist.length() < 4) {
                if (containsEither(p, queryArtist)) {
                    return true;
                }
            }
        }
        return false;
    }

    public static boolean isCjk(char c) {
        return Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN;
    }

    static double jaccard(String a, String b) {
        final java.util.Set<String> ta = tokens(a);
        final java.util.Set<String> tb = tokens(b);
        if (ta.isEmpty() || tb.isEmpty()) {
            return 0;
        }
        int inter = 0;
        for (String t : ta) {
            if (tb.contains(t)) {
                inter++;
            }
        }
        final int union = ta.size() + tb.size() - inter;
        return union == 0 ? 0 : (double) inter / union;
    }

    private static java.util.Set<String> tokens(String s) {
        final java.util.Set<String> out = new java.util.LinkedHashSet<>();
        final StringBuilder cur = new StringBuilder();
        for (int i = 0; i < s.length(); ) {
            final int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isLetterOrDigit(cp)) {
                cur.appendCodePoint(cp);
            } else {
                if (cur.length() > 0) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
            }
        }
        if (cur.length() > 0) {
            out.add(cur.toString());
        }
        return out;
    }

    /**
     * High-match gate for first display / fallback eligibility.
     * Channel A: aggregate score ≥ 7; Channel B: exact title with no counter-evidence.
     * Artist mismatch is a hard veto.
     */
    public static boolean isHighMatch(MatchVerdict v) {
        if (v.title() == Evidence.NONE || v.title() == Evidence.MISSING) {
            return false;
        }
        if (v.artist() == Evidence.MISMATCH) {
            return false;
        }
        if (v.score() >= 7) {
            if (v.duration() == Evidence.MISMATCH && v.artist() != Evidence.MATCH) {
                return false;
            }
            return true;
        }
        return v.title() == Evidence.EQUAL
                && v.duration() != Evidence.MISMATCH
                && v.album() != Evidence.MISMATCH;
    }

    public static MatchVerdict evaluate(String title, String artist, long durationSec,
                                        TrackInfo track) {
        return prepare(track).evaluate(title, artist, durationSec, null);
    }

    public static MatchVerdict evaluate(String title, String artist, long durationSec,
                                        @Nullable String album, TrackInfo track) {
        return prepare(track).evaluate(title, artist, durationSec, album);
    }

    public static int scoreTrackCandidate(String title, String artist, long durationSec,
                                          TrackInfo track) {
        return prepare(track).evaluate(title, artist, durationSec, null).score();
    }

    public static int scoreTrackCandidate(String title, String artist, long durationSec,
                                          @Nullable String album, TrackInfo track) {
        return prepare(track).evaluate(title, artist, durationSec, album).score();
    }

    public static int syncRank(Lyrics lyrics) {
        for (LyricsLine line : lyrics.lines()) {
            if (line.hasWords()) {
                return 2;
            }
        }
        return lyrics.synced() ? 1 : 0;
    }

    public static int scoreLyricsCandidate(String title, String artist, long durationSec,
                                     Lyrics lyrics, TrackInfo track) {
        return scoreTrackCandidate(title, artist, durationSec, track) + syncRank(lyrics);
    }

    /**
     * Queue composite: match score dominates; sync rank breaks ties;
     * a query-variant penalty keeps original metadata ahead of derived variants.
     */
    public static int composite(int matchScore, Lyrics lyrics, int variantPenalty) {
        return matchScore * 10 + syncRank(lyrics) - variantPenalty;
    }

    public static int scoreSingleResult(Lyrics lyrics) {
        return 6 + syncRank(lyrics);
    }
}
