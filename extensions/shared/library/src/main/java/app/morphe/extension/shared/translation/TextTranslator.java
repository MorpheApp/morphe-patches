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
     * Romanization (transliteration to Latin script) of the source text. The target
     * language is irrelevant to the {@code dt=rm} field, so a dummy target is used while
     * {@code sl=auto} lets Google detect the script to romanize.
     */
    private static final String GOOGLE_ROMANIZE_URL =
            "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&dt=t&dt=rm&tl=en";

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
        return requestLines(lines, GOOGLE_TRANSLATE_URL + targetLanguage, false);
    }

    /**
     * Romanizes one batch of lines (transliterates the source script into Latin).
     * Always call off the main thread.
     *
     * @return Romanized lines, in the order they were given.
     */
    @NonNull
    public static List<String> romanize(@NonNull List<String> lines) throws Exception {
        return requestLines(lines, GOOGLE_ROMANIZE_URL, true);
    }

    /**
     * Sends one batch to the public endpoint and returns the per-line result. Shared by
     * {@link #translate} and {@link #romanize}; the only difference is which field of each
     * sentence Google returns (the translation, or the romanization at index 3/2).
     */
    @NonNull
    private static List<String> requestLines(@NonNull List<String> lines,
                                             @NonNull String url,
                                             boolean romanize) throws Exception {
        Utils.verifyOffMainThread();
        final long startTime = System.currentTimeMillis();

        StringBuilder joined = new StringBuilder(100 * lines.size());
        for (String line : lines) {
            //noinspection SizeReplaceableByIsEmpty
            if (joined.length() > 0) {
                joined.append('\n');
            }
            joined.append(line);
        }

        final String body = "q=" + URLEncoder.encode(joined.toString(), StandardCharsets.UTF_8.name());

        Exception lastFailure = null;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            if (attempt > 0) {
                sleepQuietly(INITIAL_BACKOFF_MILLISECONDS * attempt);
            }

            final int attemptNumber = attempt + 1;
            HttpURLConnection connection = null;
            try {
                connection = Requester.openConnection(url);
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(CONNECT_TIMEOUT_MILLISECONDS);
                connection.setReadTimeout(READ_TIMEOUT_MILLISECONDS);
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                connection.setRequestProperty("User-Agent", "Mozilla/5.0");
                connection.setDoOutput(true);

                //noinspection CharsetObjectCanBeUsed
                byte[] payload = body.getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(payload.length);
                try (OutputStream stream = connection.getOutputStream()) {
                    stream.write(payload);
                }

                final int code = connection.getResponseCode();
                if (code == 200) {
                    // Response: [[["translated","original",null,"romanized",...],...],...]
                    // The endpoint splits into sentences; concatenating restores the lines that were sent.
                    JSONArray sentences = new JSONArray(Requester.parseString(connection)).getJSONArray(0);
                    StringBuilder result = new StringBuilder();
                    for (int i = 0, length = sentences.length(); i < length; i++) {
                        JSONArray sentence = sentences.getJSONArray(i);
                        if (romanize) {
                            // The romanization lives at index 3, falling back to index 2.
                            String romanized = sentence.optString(3);
                            if (romanized.isEmpty() || romanized.equals("null")) {
                                romanized = sentence.optString(2);
                            }
                            if (romanized.equals("null")) {
                                romanized = "";
                            }
                            result.append(romanized);
                        } else {
                            result.append(sentence.getString(0));
                        }
                    }

                    return Arrays.asList(result.toString().split("\n", -1));
                }

                // A non-2xx response: read the body through the error stream, because
                // parseString() would throw on the error stream and hide the real status.
                String response = Requester.parseErrorString(connection);
                String responseStart = response.substring(0,
                        Math.min(response.length(), MAXIMUM_ERROR_CHARACTERS));
                TranslationHttpException httpFailure = new TranslationHttpException(code,
                        (romanize ? "Romanization" : "Translation") + " HTTP status: " + code
                                + " response: " + responseStart);
                if (!isRetryable(code) || attempt == MAX_ATTEMPTS - 1) {
                    throw httpFailure;
                }
                lastFailure = httpFailure;
            } catch (IOException ex) {
                if (attempt == MAX_ATTEMPTS - 1) {
                    throw ex;
                }
                lastFailure = ex;
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }

        throw lastFailure != null ? lastFailure : new IOException(
                (romanize ? "Romanization" : "Translation") + " failed after " + MAX_ATTEMPTS + " attempts");
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
