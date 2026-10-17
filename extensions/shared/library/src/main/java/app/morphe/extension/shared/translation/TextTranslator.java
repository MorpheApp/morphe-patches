/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2269
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.shared.translation;

import androidx.annotation.NonNull;

import org.json.JSONArray;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.requests.Requester;

/**
 * Machine translation of plain text lines, using the public Google endpoint.
 *
 * <p>Lines are sent joined by newlines and come back in the same order, so the
 * caller can map them back one to one.
 */
public final class TextTranslator {

    private static final String GOOGLE_TRANSLATE_URL =
            "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&dt=t&tl=";

/**
 * Romanization (transliteration to Latin script). A concrete target code makes Google
 * transcribe the translation, while the default leaves the source language in charge;
 * {@code sl=auto} stays because naming the source makes some languages answer with nothing.
 */
    private static final String GOOGLE_ROMANIZE_URL =
            "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&dt=t&dt=rm&tl=";

    /**
     * {@code gtx} is rate limited by address in bursts that outlive a retry with backoff, and
     * a limited address gets no answer at all until the burst passes. The client the web
     * translator itself uses answers the same requests over the same host in that situation,
     * so it is worth one try once the primary has spent its whole round. Both parse to the
     * same response shape, which is the only part the caller depends on.
     */
    private static final String GOOGLE_TRANSLATE_FALLBACK_URL =
            "https://translate.googleapis.com/translate_a/single?client=dict-chrome-ex&sl=auto&dt=t&tl=";

    private static final String GOOGLE_ROMANIZE_FALLBACK_URL =
            "https://translate.googleapis.com/translate_a/single?client=dict-chrome-ex&sl=auto&dt=t&dt=rm&tl=";

    /** A translation nobody reads, so the source keeps its own script. */
    private static final String DEFAULT_ROMANIZATION_TARGET = "en";

    /** Japanese is the one language whose romanization arrives as one run, and a bar survives it. */
    private static final String LINE_FALLBACK_SEPARATOR = " | ";

    private static final int CONNECT_TIMEOUT_MILLISECONDS = 10_000;
    private static final int READ_TIMEOUT_MILLISECONDS = 15_000;

    /**
     * The public endpoint is rate limited and intermittently returns 4xx/5xx, so a short
     * retry with backoff turns most transient blips into successful translations.
     */
    private static final int MAX_ATTEMPTS = 3;
    private static final long INITIAL_BACKOFF_MILLISECONDS = 500;

    /**
     * Batches are built by character budget rather than line count, so request
     * sizes stay uniform regardless of how long the lines are.
     */
    public static final int MAXIMUM_BATCH_CHARACTERS = 4_000;

    /** A rate limited response is a whole HTML page, of which only the start is worth logging. */
    private static final int MAXIMUM_ERROR_CHARACTERS = 200;

    private TextTranslator() {
    }

    /**
     * Raised when the endpoint answers with anything other than 200, so callers can
     * tell a rate limit from a network failure and react to the status code.
     */
    public static final class TranslationHttpException extends Exception {
        public final int statusCode;

        public TranslationHttpException(int statusCode, @NonNull String message) {
            super(message);
            this.statusCode = statusCode;
        }
    }

    /**
     * Splits lines into batches that each stay within {@link #MAXIMUM_BATCH_CHARACTERS}.
     * A single line longer than the budget is kept in a batch of its own.
     *
     * @param budget Maximum characters per batch.
     */
    @NonNull
    public static List<List<String>> splitByCharacterBudget(@NonNull List<String> lines, int budget) {
        List<List<String>> batches = new ArrayList<>();
        List<String> current = new ArrayList<>();
        int currentLength = 0;

        for (String line : lines) {
            final int length = line.length() + 1;
            if (!current.isEmpty() && currentLength + length > budget) {
                batches.add(current);
                current = new ArrayList<>();
                currentLength = 0;
            }
            current.add(line);
            currentLength += length;
        }

        if (!current.isEmpty()) {
            batches.add(current);
        }
        return batches;
    }

    /**
     * Translates one batch of lines. Always call off the main thread.
     *
     * @param targetLanguage Language code such as {@code uk}.
     * @return Translated lines, in the order they were given.
     */
    @NonNull
    public static List<String> translate(@NonNull List<String> lines, @NonNull String targetLanguage)
            throws Exception {
        return requestLines(lines, GOOGLE_TRANSLATE_URL + targetLanguage,
                GOOGLE_TRANSLATE_FALLBACK_URL + targetLanguage, false, false, "\n");
    }

/**
 * Romanizes one batch of lines. Always call off the main thread.
 *
 * @param targetLanguage Language code such as {@code yue}, or {@code DEFAULT} for the
 *                       language the lyrics are written in.
 */
    @NonNull
    public static List<String> romanize(@NonNull List<String> lines,
                                        @NonNull String targetLanguage) throws Exception {
        final String url = GOOGLE_ROMANIZE_URL + targetCode(targetLanguage);
        final String fallbackUrl = GOOGLE_ROMANIZE_FALLBACK_URL + targetCode(targetLanguage);
        List<String> romanized = requestLines(lines, url, fallbackUrl, true,
                preferTranslated(targetLanguage), "\n");
        if (romanized.size() == lines.size() || containsSeparator(lines)) {
            return romanized;
        }

        // A count that does not match leaves the caller nothing to map, so the bar is worth one more request.
        List<String> split = requestLines(lines, url, fallbackUrl, true,
                preferTranslated(targetLanguage), LINE_FALLBACK_SEPARATOR);
        return split.size() == lines.size() ? split : romanized;
    }

    @NonNull
    private static String targetCode(@NonNull String targetLanguage) {
        return isDefault(targetLanguage)
                ? DEFAULT_ROMANIZATION_TARGET
                : targetLanguage.toLowerCase(Locale.ROOT);
    }

    /** A chosen language is transcribed in the translation, which Google keeps in the other field. */
    private static boolean preferTranslated(@NonNull String targetLanguage) {
        return !isDefault(targetLanguage);
    }

    private static boolean isDefault(@NonNull String targetLanguage) {
        return "DEFAULT".equalsIgnoreCase(targetLanguage);
    }

    /** Whether a line already holds a bar, which would make the fallback split it wrongly. */
    private static boolean containsSeparator(@NonNull List<String> lines) {
        for (String line : lines) {
            if (line.contains(LINE_FALLBACK_SEPARATOR) || line.contains("|")) {
                return true;
            }
        }
        return false;
    }

/**
 * Sends one batch and returns the per-line result. A rate limit outlives a short backoff,
 * so moving on to the other client is only worth trying once the retries are spent.
 */
    @NonNull
    private static List<String> requestLines(@NonNull List<String> lines,
                                             @NonNull String url,
                                             @NonNull String fallbackUrl,
                                             boolean romanize,
                                             boolean preferTranslatedRomanization,
                                             @NonNull String lineSeparator) throws Exception {
        Exception lastFailure = null;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            if (attempt > 0) {
                sleepQuietly(INITIAL_BACKOFF_MILLISECONDS * attempt);
            }

            try {
                return sendBatch(lines, url, romanize, preferTranslatedRomanization, lineSeparator);
            } catch (TranslationHttpException ex) {
                lastFailure = ex;
                if (!isRetryable(ex.statusCode)) break;
            } catch (IOException ex) {
                lastFailure = ex;
            }
        }

        try {
            return sendBatch(lines, fallbackUrl, romanize, preferTranslatedRomanization, lineSeparator);
        } catch (Exception fallbackFailure) {
            Logger.printDebug(() -> "The fallback client failed as well", fallbackFailure);
        }

        throw lastFailure != null ? lastFailure : new IOException(
                (romanize ? "Romanization" : "Translation") + " failed after " + MAX_ATTEMPTS + " attempts");
    }

    @NonNull
    private static List<String> sendBatch(@NonNull List<String> lines,
                                          @NonNull String url,
                                          boolean romanize,
                                          boolean preferTranslatedRomanization,
                                          @NonNull String lineSeparator) throws Exception {
        Utils.verifyOffMainThread();

        StringBuilder joined = new StringBuilder(100 * lines.size());
        for (String line : lines) {
            //noinspection SizeReplaceableByIsEmpty
            if (joined.length() > 0) {
                joined.append(lineSeparator);
            }
            joined.append(line);
        }

        String body = "q=" + URLEncoder.encode(joined.toString(), "UTF-8");

        HttpURLConnection connection = null;
        try {
            connection = Requester.openConnection(url);
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MILLISECONDS);
            connection.setReadTimeout(READ_TIMEOUT_MILLISECONDS);
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            connection.setRequestProperty("User-Agent", "Mozilla/5.0");
            connection.setDoOutput(true);

            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(payload.length);
            try (OutputStream stream = connection.getOutputStream()) {
                stream.write(payload);
            }

            final int code = connection.getResponseCode();
            if (code == 200) {
                // Response: [[["translated","original",null,"romanized",...],...],...]
                JSONArray sentences = new JSONArray(Requester.parseString(connection)).getJSONArray(0);
                if (romanize) {
                    return extractRomanizedLines(sentences, preferTranslatedRomanization, lineSeparator);
                }

                StringBuilder result = new StringBuilder();
                for (int i = 0, length = sentences.length(); i < length; i++) {
                    result.append(sentences.getJSONArray(i).getString(0));
                }

                return Arrays.asList(result.toString().split("\n", -1));
            }

            // A non-2xx response: read the body through the error stream, because
            // parseString() would throw on the error stream and hide the real status.
            String response = Requester.parseErrorString(connection);
            String responseStart = response.substring(0,
                    Math.min(response.length(), MAXIMUM_ERROR_CHARACTERS));
            throw new TranslationHttpException(code,
                    (romanize ? "Romanization" : "Translation") + " HTTP status: " + code
                            + " response: " + responseStart);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * One romanization covers the whole batch and arrives after the per-line segments: index 3
     * holds the source, index 2 the translation, and only one of them is ever asked for.
     */
    @NonNull
    private static List<String> extractRomanizedLines(@NonNull JSONArray sentences,
                                                      boolean preferTranslated,
                                                      @NonNull String lineSeparator) {
        final JSONArray last = sentences.optJSONArray(sentences.length() - 1);
        final String source = last != null ? last.optString(3) : "";
        final String translated = last != null ? last.optString(2) : "";
        final String first = preferTranslated ? translated : source;
        final String second = preferTranslated ? source : translated;
        String romanized = first.isEmpty() ? second : first;
        if ("null".equals(romanized)) {
            romanized = "";
        }
        return splitRomanized(romanized, lineSeparator);
    }

    /** Cuts the batch romanization back into the lines that were sent. */
    @NonNull
    private static List<String> splitRomanized(@NonNull String romanized,
                                              @NonNull String lineSeparator) {
        if (LINE_FALLBACK_SEPARATOR.equals(lineSeparator)) {
            List<String> lines = new ArrayList<>();
            for (String line : romanized.split("\\|", -1)) {
                lines.add(line.trim());
            }
            return lines;
        }
        return Arrays.asList(romanized.split("\n", -1));
    }

    private static boolean isRetryable(int code) {
        return code == 403 || code == 404 || code == 429 || (code >= 500 && code <= 599);
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
