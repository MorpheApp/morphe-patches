/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4213
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.requests.Requester;

/**
 * Blocking translation of an ordered batch of caption text, using Google with Edge as fallback.
 * Must be called on a worker thread. Scheduling belongs to the {@link CaptionSession}.
 */
final class CaptionBlockTranslator implements CaptionSession.Translator {
    interface ConnectionFactory {
        HttpURLConnection open(String url) throws IOException;
    }

    interface Clock {
        long millis();

        default void pause(long millis) throws InterruptedException {
            Thread.sleep(millis);
        }
    }

    static final class Failure extends IOException {
        final long retryAfterMs;
        final boolean retryable;

        Failure(String message, long retryAfterMs, boolean retryable) {
            super(message);
            this.retryAfterMs = retryAfterMs;
            this.retryable = retryable;
        }
    }

    private enum Provider {
        GOOGLE, MICROSOFT
    }

    static final int MAX_BATCH_TEXTS = 5;

    static final CaptionBlockTranslator DEFAULT = new CaptionBlockTranslator(
            url -> Requester.openConnection(url), () -> System.nanoTime() / 1_000_000L);

    private static final String GOOGLE_URL = "https://translate-pa.googleapis.com/v1/translateHtml";
    private static final String GOOGLE_API_KEY = "AIzaSyATBXajvzQLTDHEQbcpq0Ihe0vWDHmO520";
    private static final String MICROSOFT_URL = "https://edge.microsoft.com/translate/translatetext";

    private static final int BUDGET_MS = 8000;
    private static final int MAX_RESPONSE_BYTES = 256 * 1024;
    private static final int MAX_OUTPUT_CHARS = 4096;
    private static final int CACHE_CAPACITY = 256;

    private static final long DEFAULT_COOLDOWN_SECONDS = 60;
    private static final long MAX_COOLDOWN_SECONDS = 300;
    private static final long VERIFICATION_COOLDOWN_MS = 60000;

    /**
     * Allows twenty initial Google requests and then eight per second.
     * All targets share the allowance.
     */
    private static final long GOOGLE_REQUEST_COST_MS = 125;
    private static final long GOOGLE_MAX_CREDIT_MS = 2500;

    private final ConnectionFactory connections;
    private final Clock clock;
    private final Map<String, String> cache = new LinkedHashMap<>(CACHE_CAPACITY, .75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> entry) {
            return size() > CACHE_CAPACITY;
        }
    };
    private volatile long microsoftCooldownUntil, googleCooldownUntil;
    private final Map<HttpURLConnection, BooleanSupplier> pendingConnections = new IdentityHashMap<>();
    private long googleCreditMs = GOOGLE_MAX_CREDIT_MS, googleCreditUpdated;

    CaptionBlockTranslator(ConnectionFactory connections, Clock clock) {
        this.connections = connections;
        this.clock = clock;
        googleCreditUpdated = clock.millis();
    }

    /**
     * Disconnects pending requests whose sessions are no longer active.
     * Must be called on a worker thread. Does not disconnect a request of a replacement session.
     */
    void cancelInactive() {
        List<HttpURLConnection> inactive = new ArrayList<>();
        synchronized (this) {
            for (var entry : pendingConnections.entrySet()) {
                if (!entry.getValue().getAsBoolean()) inactive.add(entry.getKey());
            }
        }
        for (HttpURLConnection connection : inactive) connection.disconnect();
    }

    @Override
    public List<String> translate(String sourceLanguage, String targetLanguage, List<String> sourceTexts)
            throws IOException {
        return translate(sourceLanguage, targetLanguage, sourceTexts, () -> true);
    }

    /** Memory-only lookup so a caption mode change can reuse already prepared translations. */
    @Override
    @Nullable
    public String cached(String source, String target, String text) {
        String key = key(source.toLowerCase(Locale.ROOT), target.toLowerCase(Locale.ROOT), text);
        synchronized (cache) {
            return cache.get(key);
        }
    }

    /**
     * @return Translations in the order of the texts. A translation that is unavailable is null.
     * @throws IOException If no translation is available.
     * @throws CancellationException If active returns false.
     */
    @Override
    public List<String> translate(String sourceLanguage, String targetLanguage, List<String> sourceTexts,
                                  BooleanSupplier active) throws IOException {
        checkActive(active);
        String source = language(sourceLanguage);
        String target = language(targetLanguage);
        if (sourceTexts == null || sourceTexts.isEmpty() || sourceTexts.size() > MAX_BATCH_TEXTS) {
            throw new IOException("Caption batch must contain 1.." + MAX_BATCH_TEXTS + " texts");
        }
        List<String> texts = new ArrayList<>(sourceTexts);
        for (String text : texts) {
            validateText(text, StableCaptionBlockPolicy.MAX_BLOCK_CHARS);
        }
        if (source.equals(target)) return Collections.unmodifiableList(texts);

        List<String> result = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String text : texts) {
            String value;
            synchronized (cache) {
                value = cache.get(key(source, target, text));
            }
            result.add(value);
            if (value == null) missing.add(text);
        }
        if (missing.isEmpty()) return Collections.unmodifiableList(result);

        final long deadline = clock.millis() + BUDGET_MS;
        List<String> translated;
        IOException failure = null;
        try {
            translated = google(source, target, missing, Math.min(deadline, clock.millis() + BUDGET_MS / 2), active);
        } catch (IOException unavailable) {
            failure = unavailable;
            translated = new ArrayList<>(Collections.nCopies(missing.size(), null));
        }

        List<String> fallbackTexts = new ArrayList<>();
        for (int i = 0; i < missing.size(); i++) {
            if (translated.get(i) == null) fallbackTexts.add(missing.get(i));
        }
        checkActive(active);
        if (!fallbackTexts.isEmpty()) {
            try {
                List<String> fallback = microsoft(source, target, fallbackTexts, deadline, active);
                int next = 0;
                for (int i = 0; i < translated.size(); i++) {
                    if (translated.get(i) == null) translated.set(i, fallback.get(next++));
                }
            } catch (IOException unavailable) {
                failure = failure == null ? unavailable : combinedFailure(failure, unavailable);
            }
        }
        checkActive(active);

        // Each validated result has a known source index. A failed sibling must not erase it.
        int next = 0;
        boolean available = false;
        synchronized (cache) {
            for (int i = 0; i < result.size(); i++) {
                if (result.get(i) == null) {
                    String value = translated.get(next++);
                    result.set(i, value);
                    if (value != null) cache.put(key(source, target, texts.get(i)), value);
                }
                available |= result.get(i) != null;
            }
        }
        if (!available) {
            throw failure == null ? new Failure("No valid caption translation", 0, false) : failure;
        }
        return Collections.unmodifiableList(result);
    }

    private static Failure combinedFailure(IOException first, IOException second) {
        final long firstDelay = first instanceof Failure typed ? typed.retryAfterMs : 0;
        final long secondDelay = second instanceof Failure typed ? typed.retryAfterMs : 0;
        final boolean retryable = !(first instanceof Failure firstTyped && !firstTyped.retryable
                && second instanceof Failure secondTyped && !secondTyped.retryable);
        return new Failure("Caption providers unavailable", Math.max(firstDelay, secondDelay), retryable);
    }

    private List<String> microsoft(String source, String target, List<String> texts, long deadline,
                                   BooleanSupplier active) throws IOException {
        if (clock.millis() < microsoftCooldownUntil) {
            throw new Failure("Caption Microsoft cooling down", microsoftCooldownUntil - clock.millis(), true);
        }
        JSONArray body = new JSONArray();
        for (String text : texts) {
            body.put(escapeHtml(text));
        }
        String url = MICROSOFT_URL + "?from=" + encode(source) + "&to=" + encode(target) + "&isEnterpriseClient=false";
        String response = request(Provider.MICROSOFT, url, body.toString(), deadline, active);

        try {
            JSONArray array = new JSONArray(response);
            if (array.length() != texts.size()) throw new IOException("Caption response count mismatch");

            List<String> values = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                try {
                    values.add(microsoftTranslation(array.getJSONObject(i), target));
                } catch (JSONException | IOException ex) {
                    Logger.printInfo(() -> "Invalid Microsoft caption translation", ex);
                    values.add(null);
                }
            }
            return values;
        } catch (JSONException ex) {
            throw new IOException("Malformed caption response", ex);
        }
    }

    private static String microsoftTranslation(JSONObject item, String target) throws JSONException, IOException {
        JSONArray translations = item.getJSONArray("translations");
        if (translations.length() != 1) throw new IOException("Unexpected translation count");

        JSONObject translation = translations.getJSONObject(0);
        if (!(translation.get("to") instanceof String returnedLanguage)
                || !(translation.get("text") instanceof String returnedText)) {
            throw new IOException("Invalid caption translation fields");
        }
        if (!language(returnedLanguage).equals(target)) throw new IOException("Wrong caption language");

        return output(returnedText);
    }

    private List<String> google(String source, String target, List<String> texts, long deadline,
                                BooleanSupplier active) throws IOException {
        if (clock.millis() < googleCooldownUntil) {
            throw new Failure("Caption Google cooling down", googleCooldownUntil - clock.millis(), true);
        }
        List<String> values = new ArrayList<>();
        IOException failure = null;
        boolean available = false;
        for (String text : texts) {
            String value = null;
            try {
                if (clock.millis() < googleCooldownUntil) {
                    throw new Failure("Caption Google cooling down", googleCooldownUntil - clock.millis(), true);
                }
                JSONArray body = new JSONArray().put(new JSONArray()
                        .put(new JSONArray().put(escapeHtml(text)))
                        .put(source).put(target)).put("wt_lib");
                String response = request(Provider.GOOGLE, GOOGLE_URL, body.toString(), deadline, active);
                JSONArray translations = new JSONArray(response).getJSONArray(0);
                if (translations.length() != 1 || !(translations.get(0) instanceof String translation)) {
                    throw new IOException("Invalid Google caption response");
                }
                value = output(translation);
                available = true;
            } catch (IOException ex) {
                failure = ex;
            } catch (JSONException ex) {
                Logger.printInfo(() -> "Malformed Google caption response", ex);
                failure = new Failure("Malformed Google caption response", 0, false);
            }
            values.add(value);
        }
        if (!available) {
            throw failure == null ? new Failure("No Google caption result", 0, false) : failure;
        }
        return values;
    }

    private void paceGoogle(long deadline, BooleanSupplier active) throws IOException {
        while (true) {
            checkActive(active);
            long wait;
            synchronized (this) {
                final long now = clock.millis();
                googleCreditMs = Math.min(GOOGLE_MAX_CREDIT_MS, googleCreditMs + Math.max(0, now - googleCreditUpdated));
                googleCreditUpdated = now;
                if (googleCreditMs >= GOOGLE_REQUEST_COST_MS) {
                    googleCreditMs -= GOOGLE_REQUEST_COST_MS;
                    return;
                }
                wait = Math.min(GOOGLE_REQUEST_COST_MS - googleCreditMs, remaining(deadline));
            }
            try {
                clock.pause(wait);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new CancellationException("Caption pacing interrupted");
            }
        }
    }

    private String request(Provider provider, String url, String body, long deadline, BooleanSupplier active)
            throws IOException {
        checkActive(active);
        final boolean google = provider == Provider.GOOGLE;
        if (google) paceGoogle(deadline, active);

        final int remaining = remaining(deadline);
        HttpURLConnection connection = connections.open(url);
        try {
            synchronized (this) {
                checkActive(active);
                pendingConnections.put(connection, active);
            }
            connection.setConnectTimeout(remaining);
            connection.setReadTimeout(remaining);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("POST");
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type",
                    google ? "application/json+protobuf" : "application/json; charset=UTF-8");
            if (google) connection.setRequestProperty("X-Goog-API-Key", GOOGLE_API_KEY);
            connection.setFixedLengthStreamingMode(payload.length);
            checkActive(active);
            connection.connect();
            checkActive(active);
            connection.setReadTimeout(remaining(deadline));
            try (OutputStream stream = connection.getOutputStream()) {
                stream.write(payload);
            }
            connection.setReadTimeout(remaining(deadline));
            final int status = connection.getResponseCode();
            checkActive(active);

            if (status == HttpURLConnection.HTTP_FORBIDDEN || status == 429) {
                final long seconds = retryAfterSeconds(connection);
                final long until = clock.millis() + seconds * 1000;
                if (google) googleCooldownUntil = until;
                else microsoftCooldownUntil = until;
                throw new Failure("Caption provider HTTP " + status, seconds * 1000, status == 429);
            }
            if (google && status >= 300 && status < 400 && isVerificationPage(connection.getHeaderField("Location"))) {
                googleCooldownUntil = clock.millis() + VERIFICATION_COOLDOWN_MS;
                throw new Failure("Caption Google requires verification", VERIFICATION_COOLDOWN_MS, true);
            }
            if (status != HttpURLConnection.HTTP_OK) throw new IOException("Caption provider HTTP " + status);

            return readResponse(connection, deadline, active);
        } finally {
            synchronized (this) {
                pendingConnections.remove(connection);
            }
            closeErrorStream(connection);
            connection.disconnect();
        }
    }

    private String readResponse(HttpURLConnection connection, long deadline, BooleanSupplier active) throws IOException {
        try (InputStream stream = connection.getInputStream();
             ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            while (true) {
                checkActive(active);
                connection.setReadTimeout(remaining(deadline));
                final int count = stream.read(buffer);
                if (count == -1) break;
                if (bytes.size() + count > MAX_RESPONSE_BYTES) throw new IOException("Caption response too large");
                bytes.write(buffer, 0, count);
            }
            remaining(deadline);
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void closeErrorStream(HttpURLConnection connection) {
        InputStream error = connection.getErrorStream();
        if (error == null) return;

        try {
            error.close();
        } catch (IOException ex) {
            Logger.printDebug(() -> "Could not close caption error stream", ex);
        }
    }

    private static boolean isVerificationPage(@Nullable String location) {
        return location != null && (location.startsWith("https://www.google.com/sorry/")
                || location.startsWith("https://google.com/sorry/"));
    }

    /**
     * @return The bounded value of the Retry-After header, which is seconds or a date.
     */
    private static long retryAfterSeconds(HttpURLConnection connection) {
        long seconds = DEFAULT_COOLDOWN_SECONDS;
        String value = connection.getHeaderField("Retry-After");
        if (value != null && value.matches("[0-9]{1,9}")) {
            seconds = Long.parseLong(value);
        } else {
            final long retryAt = connection.getHeaderFieldDate("Retry-After", -1);
            if (retryAt >= 0) {
                final long serverTime = connection.getHeaderFieldDate("Date", System.currentTimeMillis());
                seconds = Math.max(0, (retryAt - serverTime + 999) / 1000);
            }
        }
        return Math.max(1, Math.min(MAX_COOLDOWN_SECONDS, seconds));
    }

    private static void checkActive(BooleanSupplier active) {
        if (!active.getAsBoolean()) throw new CancellationException("Caption session superseded");
    }

    private int remaining(long deadline) throws IOException {
        final long left = deadline - clock.millis();
        if (left <= 0) throw new IOException("Caption translation timed out");
        return (int) Math.min(BUDGET_MS, left);
    }

    private static String language(@Nullable String value) throws IOException {
        if (value == null || !value.matches("[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*")) {
            throw new IOException("Invalid caption language");
        }
        return value.toLowerCase(Locale.ROOT);
    }

    private static String key(String source, String target, String text) {
        return source + '\n' + target + '\n' + text;
    }

    private static void validateText(@Nullable String value, int maximum) throws IOException {
        if (value == null
                || value.codePoints().allMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c))
                || value.codePointCount(0, value.length()) > maximum) {
            throw new IOException("Invalid caption text");
        }
    }

    private static String escapeHtml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String output(String text) throws IOException {
        // &amp; last: an encoded entity remains encoded after exactly one decoding pass.
        String value = text.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&#39;", "'").replace("&amp;", "&");
        validateText(value, MAX_OUTPUT_CHARS);
        return value;
    }

    private static String encode(String value) throws IOException {
        return URLEncoder.encode(value, "UTF-8");
    }
}
