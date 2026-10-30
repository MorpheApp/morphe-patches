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

/** Blocking, worker-only translation of an ordered source batch. Scheduling belongs to the session. */
final class CaptionBlockTranslator {
    interface ConnectionFactory { HttpURLConnection open(String url) throws IOException; }
    interface Clock { long millis(); }
    static final CaptionBlockTranslator DEFAULT = new CaptionBlockTranslator(
            url -> Requester.openConnection(url), () -> System.nanoTime() / 1_000_000L);
    private static final int BUDGET_MS = 8000, MAX_BYTES = 256 * 1024;
    private final ConnectionFactory connections;
    private final Clock clock;
    // This instance has one fixed automatic provider policy: Edge, then keyless Google.
    private final Map<String, String> cache = new LinkedHashMap<String, String>(256, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, String> entry) {
            return size() > 256;
        }
    };
    private volatile long cooldownUntil, googleCooldownUntil;

    CaptionBlockTranslator(ConnectionFactory connections, Clock clock) {
        this.connections = connections;
        this.clock = clock;
    }

    List<String> translate(String sourceLanguage, String targetLanguage, List<String> sourceTexts)
            throws IOException {
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
        if (clock.millis() < cooldownUntil) throw new IOException("Caption provider cooling down");
        long deadline = clock.millis() + BUDGET_MS;
        List<String> translated;
        try {
            // Reserve half the total budget for fallback if the primary stalls.
            translated = microsoft(source, target, missing, Math.min(deadline, clock.millis() + BUDGET_MS / 2));
        } catch (CooldownException terminal) {
            throw terminal;
        } catch (IOException failure) {
            translated = google(source, target, missing, deadline);
        }
        // Only a fully validated batch is published or cached.
        int next = 0;
        synchronized (cache) {
            for (int i = 0; i < result.size(); i++) if (result.get(i) == null) {
                String value = translated.get(next++);
                result.set(i, value);
                cache.put(key(source, target, texts.get(i)), value);
            }
        }
        return Collections.unmodifiableList(result);
    }

    private List<String> microsoft(String source, String target, List<String> texts, long deadline)
            throws IOException {
        JSONArray body = new JSONArray();
        for (String text : texts) body.put(text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"));
        String response = request("https://edge.microsoft.com/translate/translatetext?from="
                + encode(source) + "&to=" + encode(target) + "&isEnterpriseClient=false", body.toString(), deadline);
        try {
            JSONArray array = new JSONArray(response);
            if (array.length() != texts.size()) throw new IOException("Caption response count mismatch");
            List<String> values = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                JSONArray translations = array.getJSONObject(i).getJSONArray("translations");
                if (translations.length() != 1) throw new IOException("Unexpected translation count");
                JSONObject item = translations.getJSONObject(0);
                // Android getString coerces JSON null/numbers/objects; require actual strings.
                Object returnedLanguage = item.get("to"), returnedText = item.get("text");
                if (!(returnedLanguage instanceof String) || !(returnedText instanceof String))
                    throw new IOException("Invalid caption translation fields");
                if (!language((String) returnedLanguage).equals(target)) throw new IOException("Wrong caption language");
                values.add(output((String) returnedText));
            }
            return values;
        } catch (JSONException e) { throw new IOException("Malformed caption response", e); }
    }

    private List<String> google(String source, String target, List<String> texts, long deadline)
            throws IOException {
        if (clock.millis() < googleCooldownUntil) throw new IOException("Caption fallback cooling down");
        List<String> values = new ArrayList<>();
        for (String text : texts) {
            String response = request("https://translate.googleapis.com/translate_a/single?client=gtx&sl="
                    + encode(source) + "&tl=" + encode(target) + "&dt=t&q=" + encode(text), null, deadline);
            try {
                JSONArray segments = new JSONArray(response).getJSONArray(0);
                StringBuilder value = new StringBuilder();
                for (int i = 0; i < segments.length(); i++) {
                    Object segment = segments.getJSONArray(i).get(0);
                    if (!(segment instanceof String)) throw new IOException("Invalid caption segment");
                    value.append((String) segment);
                }
                values.add(output(value.toString()));
            } catch (JSONException e) { throw new IOException("Malformed caption fallback", e); }
        }
        return values;
    }

    private String request(String url, String body, long deadline) throws IOException {
        int remaining = remaining(deadline);
        HttpURLConnection connection = connections.open(url);
        try {
            connection.setConnectTimeout(remaining);
            connection.setReadTimeout(remaining);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod(body == null ? "GET" : "POST");
            byte[] payload = body == null ? null : body.getBytes(StandardCharsets.UTF_8);
            if (payload != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                connection.setFixedLengthStreamingMode(payload.length);
            }
            connection.connect();
            connection.setReadTimeout(remaining(deadline));
            if (payload != null) {
                try (OutputStream stream = connection.getOutputStream()) {
                    stream.write(payload);
                }
            }
            connection.setReadTimeout(remaining(deadline));
            int status = connection.getResponseCode();
            if (status == 403 || status == 429) {
                long seconds = 60;
                try { seconds = Long.parseLong(connection.getHeaderField("Retry-After")); }
                catch (NumberFormatException ignored) {}
                seconds = Math.max(1, Math.min(300, seconds));
                cooldownUntil = clock.millis() + seconds * 1000;
                throw new CooldownException();
            }
            String location = connection.getHeaderField("Location");
            if (status >= 300 && status < 400 && url.startsWith("https://translate.googleapis.com/")
                    && location != null && (location.startsWith("https://www.google.com/sorry/")
                            || location.startsWith("https://google.com/sorry/"))) {
                googleCooldownUntil = clock.millis() + 60000;
                throw new IOException("Caption fallback requires verification");
            }
            if (status != 200) throw new IOException("Caption provider HTTP " + status);
            try (InputStream stream = connection.getInputStream();
                 ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int count;
                while (true) {
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
            InputStream error = connection.getErrorStream();
            if (error != null) try { error.close(); } catch (IOException ignored) {}
            connection.disconnect();
        }
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
    private static final class CooldownException extends IOException {
        CooldownException() { super("Caption provider rate limited or forbidden"); }
    }
}
