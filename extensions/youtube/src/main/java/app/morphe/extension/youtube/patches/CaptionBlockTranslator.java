/* Copyright 2026 Morphe. See NOTICE for GPLv3 Section 7 terms. */
package app.morphe.extension.youtube.patches;

import app.morphe.extension.shared.requests.Requester;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.concurrent.CancellationException;

/** Blocking, worker-only translation of an ordered source batch. Scheduling belongs to the session. */
final class CaptionBlockTranslator {
    interface ConnectionFactory { HttpURLConnection open(String url) throws IOException; }
    interface Clock {
        long millis();
        default void pause(long millis) throws InterruptedException { Thread.sleep(millis); }
    }
    static final class Failure extends IOException {
        final long retryAfterMs;
        final boolean retryable;
        Failure(String message, long retryAfterMs, boolean retryable) {
            super(message); this.retryAfterMs = retryAfterMs; this.retryable = retryable;
        }
    }
    static final CaptionBlockTranslator DEFAULT = new CaptionBlockTranslator(
            url -> Requester.openConnection(url), () -> System.nanoTime() / 1_000_000L);
    private static final String GOOGLE_API_KEY = "AIzaSyATBXajvzQLTDHEQbcpq0Ihe0vWDHmO520";
    private static final int BUDGET_MS = 8000, MAX_BYTES = 256 * 1024;
    private final ConnectionFactory connections;
    private final Clock clock;
    // This instance has one fixed automatic provider policy: Google, then Edge.
    private final Map<String, String> cache = new LinkedHashMap<String, String>(256, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, String> entry) {
            return size() > 256;
        }
    };
    private volatile long microsoftCooldownUntil, googleCooldownUntil;
    private HttpURLConnection pendingConnection;
    private BooleanSupplier pendingActive;
    // Twenty initial Google requests, then eight per second; all targets share the allowance.
    private long googleCreditMs = 2500, googleCreditUpdated;

    CaptionBlockTranslator(ConnectionFactory connections, Clock clock) {
        this.connections = connections;
        this.clock = clock;
        googleCreditUpdated = clock.millis();
    }

    /** Worker-only: a delayed cancellation must never disconnect a replacement session. */
    void cancelInactive() {
        HttpURLConnection connection;
        synchronized (this) {
            if (pendingConnection == null || pendingActive.getAsBoolean()) return;
            connection = pendingConnection;
        }
        connection.disconnect();
    }

    List<String> translate(String sourceLanguage, String targetLanguage, List<String> sourceTexts)
            throws IOException {
        return translate(sourceLanguage, targetLanguage, sourceTexts, () -> true);
    }

    List<String> translate(String sourceLanguage, String targetLanguage, List<String> sourceTexts,
            BooleanSupplier active) throws IOException {
        checkActive(active);
        String source = language(sourceLanguage), target = language(targetLanguage);
        if (sourceTexts == null || sourceTexts.isEmpty() || sourceTexts.size() > 5)
            throw new IOException("Caption batch must contain 1..5 texts");
        List<String> texts = new ArrayList<>(sourceTexts);
        for (String text : texts) validateText(text, 512);
        if (source.equals(target)) return Collections.unmodifiableList(texts);
        List<String> result = new ArrayList<>(), missing = new ArrayList<>();
        for (String text : texts) {
            String value;
            synchronized (cache) { value = cache.get(key(source, target, text)); }
            result.add(value);
            if (value == null) missing.add(text);
        }
        if (missing.isEmpty()) return Collections.unmodifiableList(result);
        long deadline = clock.millis() + BUDGET_MS;
        List<String> translated;
        IOException failure = null;
        try {
            translated = google(source, target, missing, Math.min(deadline, clock.millis() + BUDGET_MS / 2), active);
        } catch (IOException unavailable) {
            failure = unavailable;
            translated = new ArrayList<>(Collections.nCopies(missing.size(), null));
        }
        List<String> fallbackTexts = new ArrayList<>();
        for (int i = 0; i < missing.size(); i++) if (translated.get(i) == null) fallbackTexts.add(missing.get(i));
        checkActive(active);
        if (!fallbackTexts.isEmpty()) {
            try {
                List<String> fallback = microsoft(source, target, fallbackTexts, deadline, active);
                int next = 0;
                for (int i = 0; i < translated.size(); i++) if (translated.get(i) == null)
                    translated.set(i, fallback.get(next++));
            } catch (IOException unavailable) {
                if (failure == null) failure = unavailable;
                else {
                    long firstDelay = failure instanceof Failure typed ? typed.retryAfterMs : 0;
                    long secondDelay = unavailable instanceof Failure typed ? typed.retryAfterMs : 0;
                    boolean retryable = !(failure instanceof Failure first && !first.retryable
                            && unavailable instanceof Failure second && !second.retryable);
                    failure = new Failure("Caption providers unavailable", Math.max(firstDelay, secondDelay), retryable);
                }
            }
        }
        checkActive(active);
        // Each validated result has a known source index; a failed sibling must not erase it.
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
        if (!available) throw failure == null ? new Failure("No valid caption translation", 0, false) : failure;
        return Collections.unmodifiableList(result);
    }

    private List<String> microsoft(String source, String target, List<String> texts, long deadline, BooleanSupplier active)
            throws IOException {
        if (clock.millis() < microsoftCooldownUntil)
            throw new Failure("Caption Microsoft cooling down", microsoftCooldownUntil - clock.millis(), true);
        JSONArray body = new JSONArray();
        for (String text : texts) body.put(text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"));
        String response = request("https://edge.microsoft.com/translate/translatetext?from="
                + encode(source) + "&to=" + encode(target) + "&isEnterpriseClient=false", body.toString(), deadline, active);
        try {
            JSONArray array = new JSONArray(response);
            if (array.length() != texts.size()) throw new IOException("Caption response count mismatch");
            List<String> values = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                try {
                    JSONArray translations = array.getJSONObject(i).getJSONArray("translations");
                    if (translations.length() != 1) throw new IOException("Unexpected translation count");
                    JSONObject item = translations.getJSONObject(0);
                    Object returnedLanguage = item.get("to"), returnedText = item.get("text");
                    if (!(returnedLanguage instanceof String) || !(returnedText instanceof String))
                        throw new IOException("Invalid caption translation fields");
                    if (!language((String) returnedLanguage).equals(target)) throw new IOException("Wrong caption language");
                    values.add(output((String) returnedText));
                } catch (JSONException | IOException invalidItem) { values.add(null); }
            }
            return values;
        } catch (JSONException e) { throw new IOException("Malformed caption response", e); }
    }

    private List<String> google(String source, String target, List<String> texts, long deadline, BooleanSupplier active)
            throws IOException {
        if (clock.millis() < googleCooldownUntil)
            throw new Failure("Caption Google cooling down", googleCooldownUntil - clock.millis(), true);
        List<String> values = new ArrayList<>();
        IOException failure = null;
        for (String text : texts) {
            try {
                if (clock.millis() < googleCooldownUntil)
                    throw new Failure("Caption Google cooling down", googleCooldownUntil - clock.millis(), true);
                JSONArray body = new JSONArray().put(new JSONArray()
                        .put(new JSONArray().put(text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")))
                        .put(source).put(target)).put("wt_lib");
                String response = request("https://translate-pa.googleapis.com/v1/translateHtml",
                        body.toString(), deadline, active);
                JSONArray translations = new JSONArray(response).getJSONArray(0);
                if (translations.length() != 1 || !(translations.get(0) instanceof String value))
                    throw new IOException("Invalid Google caption response");
                values.add(output(value));
            } catch (JSONException | IOException invalidItem) {
                failure = invalidItem instanceof IOException io ? io : new Failure("Malformed Google caption response", 0, false);
                values.add(null);
            }
        }
        if (values.stream().allMatch(value -> value == null))
            throw failure == null ? new Failure("No Google caption result", 0, false) : failure;
        return values;
    }

    private void paceGoogle(long deadline, BooleanSupplier active) throws IOException {
        for (;;) {
            checkActive(active);
            long wait;
            synchronized (this) {
                long now = clock.millis();
                googleCreditMs = Math.min(2500, googleCreditMs + Math.max(0, now - googleCreditUpdated));
                googleCreditUpdated = now;
                if (googleCreditMs >= 125) { googleCreditMs -= 125; return; }
                wait = Math.min(125 - googleCreditMs, remaining(deadline));
            }
            try { clock.pause(wait); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new CancellationException("Caption pacing interrupted");
            }
        }
    }

    private String request(String url, String body, long deadline, BooleanSupplier active) throws IOException {
        checkActive(active);
        if (url.startsWith("https://translate-pa.googleapis.com/")) paceGoogle(deadline, active);
        int remaining = remaining(deadline);
        HttpURLConnection connection = connections.open(url);
        try {
            synchronized (this) {
                checkActive(active);
                pendingConnection = connection;
                pendingActive = active;
            }
            connection.setConnectTimeout(remaining);
            connection.setReadTimeout(remaining);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("POST");
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            connection.setDoOutput(true);
            boolean google = url.startsWith("https://translate-pa.googleapis.com/");
            connection.setRequestProperty("Content-Type", google ? "application/json+protobuf" : "application/json; charset=UTF-8");
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
            int status = connection.getResponseCode();
            checkActive(active);
            if (status == 403 || status == 429) {
                long seconds = 60;
                try { seconds = Long.parseLong(connection.getHeaderField("Retry-After")); }
                catch (NumberFormatException ignored) {
                    long retryAt = connection.getHeaderFieldDate("Retry-After", -1);
                    if (retryAt >= 0) seconds = Math.max(0, (retryAt
                            - connection.getHeaderFieldDate("Date", System.currentTimeMillis()) + 999) / 1000);
                }
                seconds = Math.max(1, Math.min(300, seconds));
                long until = clock.millis() + seconds * 1000;
                if (url.startsWith("https://edge.microsoft.com/")) microsoftCooldownUntil = until;
                else googleCooldownUntil = until;
                throw new Failure("Caption provider HTTP " + status, seconds * 1000, status == 429);
            }
            String location = connection.getHeaderField("Location");
            if (status >= 300 && status < 400 && url.startsWith("https://translate-pa.googleapis.com/")
                    && location != null && (location.startsWith("https://www.google.com/sorry/")
                            || location.startsWith("https://google.com/sorry/"))) {
                googleCooldownUntil = clock.millis() + 60000;
                throw new Failure("Caption Google requires verification", 60000, true);
            }
            if (status != 200) throw new IOException("Caption provider HTTP " + status);
            try (InputStream stream = connection.getInputStream();
                 ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int count;
                while (true) {
                    checkActive(active);
                    connection.setReadTimeout(remaining(deadline));
                    count = stream.read(buffer);
                    if (count == -1) break;
                    if (bytes.size() + count > MAX_BYTES) throw new IOException("Caption response too large");
                    bytes.write(buffer, 0, count);
                }
                remaining(deadline);
                return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            synchronized (this) {
                if (pendingConnection == connection) {
                    pendingConnection = null;
                    pendingActive = null;
                }
            }
            InputStream error = connection.getErrorStream();
            if (error != null) try { error.close(); } catch (IOException ignored) {}
            connection.disconnect();
        }
    }

    private static void checkActive(BooleanSupplier active) {
        if (!active.getAsBoolean()) throw new CancellationException("Caption session superseded");
    }

    private int remaining(long deadline) throws IOException {
        long left = deadline - clock.millis();
        if (left <= 0) throw new IOException("Caption translation timed out");
        return (int) Math.min(BUDGET_MS, left);
    }
    private static String language(String value) throws IOException {
        if (value == null || !value.matches("[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*"))
            throw new IOException("Invalid caption language");
        return value.toLowerCase(Locale.ROOT);
    }
    private static String key(String source, String target, String text) {
        return source + '\n' + target + '\n' + text;
    }
    private static void validateText(String value, int maximum) throws IOException {
        if (value == null || value.codePoints().allMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c)) || value.codePointCount(0, value.length()) > maximum)
            throw new IOException("Invalid caption text");
    }
    private static String output(String text) throws IOException {
        // &amp; last: an encoded entity remains encoded after exactly one decoding pass.
        String value = text.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&#39;", "'").replace("&amp;", "&");
        validateText(value, 4096);
        return value;
    }
    private static String encode(String value) throws IOException {
        return URLEncoder.encode(value, "UTF-8");
    }
}
