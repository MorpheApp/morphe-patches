/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.derive;

import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;

import app.morphe.extension.music.patches.lyrics.model.Lyrics;
import app.morphe.extension.music.patches.lyrics.model.LyricsLine;
import app.morphe.extension.music.patches.lyrics.model.Word;
import app.morphe.extension.music.patches.lyrics.parsers.LRCParser;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.requests.Requester;

public final class QuickLRCClient {

    private static final ExecutorService executor = Executors.newSingleThreadExecutor();

    private static final String ENDPOINT = "https://quicklrc.com/api/v1/transcribe";
    public static final String PROVIDER_NAME = "QuickLRC";

    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 180_000;

    private static final int MAX_REDIRECTS = 3;

    private static volatile Future<?> running;

    /** Why a run produced nothing, so the caller can say something more useful than "failed". */
    public enum Failure {
        CANCELLED,
        NO_KEY,
        UNSUPPORTED_SOURCE,
        OUT_OF_CREDITS,
        FAILED
    }

    /**
     * What a key check concluded. {@link #REJECTED} is the only one that should refuse a save.
     */
    public enum KeyCheck {
        /** The service read the key and objected to something else. */
        ACCEPTED,
        /** The service refused the key itself. */
        REJECTED,
        /** The service could not be asked, so the key is neither proven nor disproven. */
        UNREACHABLE
    }

    public interface KeyCheckCallback {
        void onChecked(KeyCheck result);
    }

    public static void checkKey(String apiKey, KeyCheckCallback callback) {
        final String key = apiKey == null ? "" : apiKey.trim();
        if (key.isEmpty()) {
            callback.onChecked(KeyCheck.REJECTED);
            return;
        }
        final Thread worker = new Thread(() -> {
            KeyCheck result;
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(ENDPOINT).openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(READ_TIMEOUT_MS);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Authorization", "Bearer " + key);
                conn.setDoOutput(true);
                conn.getOutputStream().close();

                final int code = conn.getResponseCode();
                result = code == 401 || code == 403 ? KeyCheck.REJECTED : KeyCheck.ACCEPTED;
            } catch (Exception ex) {
                Logger.printDebug(() -> "Could not check the QuickLRC key", ex);
                result = KeyCheck.UNREACHABLE;
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
            final KeyCheck answer = result;
            Utils.runOnMainThread(() -> callback.onChecked(answer));
        }, "QuickLRC key check");
        worker.setDaemon(true);
        worker.start();
    }

    public interface Callback {
        void onTranscribed(@Nullable Lyrics lyrics, @Nullable Failure failure);
    }

    private QuickLRCClient() {
    }

    @SuppressWarnings("ConstantValue")
    public static boolean isConfigured() {
        final String key = Settings.LYRICS_TRANSCRIBE_KEY.get();
        return key != null && !key.trim().isEmpty();
    }

    public static boolean isRunning() {
        final Future<?> current = running;
        return current != null && !current.isDone();
    }

    public static void cancel() {
        final Future<?> current = running;
        running = null;
        if (current != null) {
            current.cancel(true);
        }
    }

    @SuppressWarnings("ConstantValue")
    public static void transcribe(String videoId, @Nullable String lyrics,
                                  BooleanSupplier stillWanted, Callback callback) {
        final String key = Settings.LYRICS_TRANSCRIBE_KEY.get();
        if (key == null || key.trim().isEmpty()) {
            Utils.runOnMainThread(() -> callback.onTranscribed(null, Failure.NO_KEY));
            return;
        }
        final String source = videoId == null ? "" : videoId.trim();
        if (source.isEmpty()) {
            Utils.runOnMainThread(() -> callback.onTranscribed(null, Failure.UNSUPPORTED_SOURCE));
            return;
        }
        final String text = lyrics == null ? "" : lyrics.trim();

        cancel();
        running = executor.submit(() -> {
            Failure failure = null;
            Lyrics result = null;
            try {
                if (stillWanted.getAsBoolean()) {
                    final String ttml = post(source, text, key.trim());
                    if (ttml == null) {
                        failure = Failure.FAILED;
                    } else {
                        result = toLyrics(ttml);
                        if (result == null) {
                            failure = Failure.FAILED;
                        }
                    }
                }
            } catch (OutOfCreditsException ex) {
                failure = Failure.OUT_OF_CREDITS;
            } catch (Exception ex) {
                // Cancelling interrupts rather than unwinds, so an interrupted read can surface as
                // an ordinary IO failure. That is a cancellation, not something to report.
                if (stillWanted.getAsBoolean()) {
                    failure = Failure.FAILED;
                } else {
                    Thread.currentThread().interrupt();
                    failure = Failure.CANCELLED;
                }
            }

            final Lyrics deliver = result;
            final Failure reason = failure;
            Utils.runOnMainThread(() -> callback.onTranscribed(deliver, reason));
        });
    }

    private static final class OutOfCreditsException extends IOException {
        OutOfCreditsException() {
            super("QuickLRC credits exhausted");
        }
    }

    private static String post(String videoId, String lyrics, String apiKey)
            throws IOException, JSONException {
        final JSONObject body = new JSONObject();
        body.put("fileUrl", "https://www.youtube.com/watch?v=" + videoId);
        body.put("format", "lrc");
        body.put("isWordLevel", true);
        if (!lyrics.isEmpty()) {
            body.put("lyrics", lyrics);
        }

        final String json = body.toString();

        String target = ENDPOINT;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            final Attempt attempt = sendOnce(target, json, apiKey);
            final int code = attempt.code();

            if (code == 307 || code == 308 || code == 301 || code == 302) {
                final String location = attempt.location();
                if (location == null || location.isEmpty()) {
                    return null;
                }
                final String next = resolve(target, location);
                if (next == null) {
                    return null;
                }
                if (next.startsWith("http://")) {
                    return null;
                }
                // Followed by hand: HttpURLConnection downgrades the POST to a GET, which is
                // what answered 405.
                target = next;
                continue;
            }
            if (code == 303) {
                return null;
            }
            if (code == 402 || code == 403) {
                // How the daily per-IP cap surfaces.
                throw new OutOfCreditsException();
            }
            if (code != 200) {
                Logger.printDebug(() -> "QuickLRC returned HTTP " + code);
                return null;
            }
            return attempt.body();
        }
        return null;
    }

    private record Attempt(int code, String message, String contentType, int contentLength,
                           String location, String body, String errorBody) {
        public Attempt {
            if (message == null) message = "";
        }
    }

    private static Attempt sendOnce(String url, String json, String apiKey) throws IOException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "text/plain");
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            conn.setDoOutput(true);
            conn.setInstanceFollowRedirects(false);

            final byte[] payload = json.getBytes(StandardCharsets.UTF_8);
            try (java.io.OutputStream out = conn.getOutputStream()) {
                out.write(payload);
            }

            final int code = conn.getResponseCode();
            final String location = conn.getHeaderField("Location");
            if (code == 200) {
                final String body = Requester.parseString(conn);
                return new Attempt(code, conn.getResponseMessage(), conn.getContentType(),
                        conn.getContentLength(), location, body, null);
            }
            return new Attempt(code, conn.getResponseMessage(), conn.getContentType(),
                    conn.getContentLength(), location, null, Requester.parseErrorString(conn));
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    @Nullable
    private static String resolve(String base, String location) {
        try {
            final java.net.URL resolved = new URL(new URL(base), location);
            return resolved.getProtocol() + "://" + resolved.getAuthority() + resolved.getPath()
                    + (resolved.getQuery() == null ? "" : "?" + resolved.getQuery());
        } catch (java.net.MalformedURLException ex) {
            return null;
        }
    }

    /**
     * Drops the gaps the service puts between CJK tokens, as in {@code <t>我 <t>靠 <t>， <t>我}.
     *
     * <p>On the body, so the cleaned text becomes the raw format and the cache and the
     * exporter store it. Hand-scanned: both sides have to agree, and no regex form that looks
     * that far past the tag matches.
     */
    private static String dropCjkSeparators(String lrc) {
        final StringBuilder out = new StringBuilder(lrc.length());
        for (int i = 0; i < lrc.length(); ) {
            if (Character.isWhitespace(lrc.charAt(i)) && isCjkGap(lrc, i)) {
                i = skipWhitespace(lrc, i);
                continue;
            }
            out.append(lrc.charAt(i));
            i++;
        }
        return out.toString();
    }

    /**
     * Whether the whitespace at {@code start} separates two CJK tokens. A tag counts as CJK
     * only through the text it wraps, which is what keeps {@code 我靠 <t>world} its space.
     */
    private static boolean isCjkGap(String lrc, int start) {
        final int before = lastNonSpaceBefore(lrc, start);
        if (before < 0) {
            return false;
        }
        final int after = firstNonSpaceAfter(lrc, skipWhitespace(lrc, start));
        if (after >= lrc.length()) {
            // At the end of body or line the character before decides alone.
            return isCjk(lrc.charAt(before));
        }
        return isCjk(lrc.charAt(before)) && isCjk(lrc.charAt(after))
                || lrc.charAt(before) == '>' && isCjk(lrc.charAt(after))
                || lrc.charAt(after) == '<' && isCjk(lrc.charAt(before))
                && isCjk(charAfterTag(lrc, after));
    }

    /**
     * The text a tag introduces. In {@code <t>我 <t>靠} the gap only faces {@code 我} and
     * {@code <}, so the other side has to be read past the tag to tell 靠 from world.
     */
    private static char charAfterTag(String lrc, int tagStart) {
        final int close = lrc.indexOf('>', tagStart);
        if (close < 0) {
            return '\0';
        }
        final int token = firstNonSpaceAfter(lrc, close + 1);
        return token < lrc.length() ? lrc.charAt(token) : '\0';
    }

    private static int skipWhitespace(String text, int index) {
        int i = index;
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        return i;
    }

    private static int lastNonSpaceBefore(String text, int index) {
        int i = index - 1;
        while (i >= 0 && Character.isWhitespace(text.charAt(i))) {
            i--;
        }
        return i;
    }

    private static int firstNonSpaceAfter(String text, int index) {
        return skipWhitespace(text, index);
    }

    // Punctuation included: {@code ，} is U+FF0C, script COMMON, so a script-based check calls
    // it non-CJK and every character after it would keep its gap.
    private static boolean isCjk(char c) {
        return (c >= '\u3000' && c <= '〿')   // CJK symbols and punctuation 、。〈〉「」
                || (c >= '\u3040' && c <= 'ヿ')   // Kana
                || (c >= 'ㇰ' && c <= 'ㇿ')
                || (c >= '一' && c <= '鿿')   // Han
                || (c >= '가' && c <= '힣')   // Hangul
                || (c >= '！' && c <= '～')   // fullwidth ，！？：；（）
                || (c >= '￠' && c <= '￦');
    }

    @SuppressWarnings("ConstantValue")
    @Nullable
    private static Lyrics toLyrics(String lrc) {
        try {
            // Cleaned first, so the raw format and everything reading it are the clean one.
            final String body = dropCjkSeparators(lrc);
            final List<LyricsLine> lines = LRCParser.parseSynced(body);
            if (lines == null || lines.isEmpty()) {
                return null;
            }
            long firstBegin = Long.MAX_VALUE;
            long lastEnd = Long.MIN_VALUE;
            for (LyricsLine line : lines) {
                if (line.startTimeMs() < firstBegin) {
                    firstBegin = line.startTimeMs();
                }
                if (line.endTimeMs() > lastEnd) {
                    lastEnd = line.endTimeMs();
                }
            }
            for (LyricsLine line : lines) {
                if (!line.hasWords() || line.words().size() > 6) {
                    continue;
                }
                StringBuilder sb = new StringBuilder();
                for (Word word : line.words()) {
                    sb.append('[').append(word.startMs()).append('-')
                            .append(word.endMs()).append(']').append(word.text());
                }
                break;
            }
            return new Lyrics(lines, PROVIDER_NAME, true, null, null, null,
                    null, body, "lrc", null);
        } catch (Exception ex) {
            return null;
        }
    }
}
