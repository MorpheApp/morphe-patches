/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2269
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.requests;

import androidx.annotation.Nullable;

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
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPInputStream;

import org.json.JSONObject;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.music.patches.lyrics.LyricsLine;
import app.morphe.extension.shared.requests.Requester;

/**
 * Shared HTTP helpers for the lyrics providers.
 */
final class LyricsRequests {

    private static final int CONNECT_TIMEOUT_MILLISECONDS = 5 * 1000;
    private static final int READ_TIMEOUT_MILLISECONDS = 5 * 1000;

    private LyricsRequests() {
    }

    static String userAgent() {
        return "Morphe/" + Utils.getAppVersionName()
                + " (" + Utils.getPatchesReleaseVersion() + ")"
                + " https://github.com/MorpheApp/morphe-patches";
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
     * Opens a POST connection with a JSON body. The caller reads the response with
     * one of the {@link app.morphe.extension.shared.requests.Requester} parse helpers.
     */
    static HttpURLConnection postJson(String url, String json) throws IOException {
        return postConnection(url, json, "application/json; charset=utf-8", null);
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

    @Nullable
    static String optString(JSONObject object, String key) {
        if (object.isNull(key)) {
            return null;
        }
        final String value = object.optString(key, "");
        return value.isBlank() ? null : value;
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
            } catch (InterruptedException ignored) {
            }
        }
        lastRequestTime.set(System.currentTimeMillis());
    }
}
