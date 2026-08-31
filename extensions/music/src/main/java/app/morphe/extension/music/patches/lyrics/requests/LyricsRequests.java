/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2269
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.requests;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.requests.Requester;

/**
 * Shared HTTP helpers for the lyrics providers.
 */
final class LyricsRequests {

    private static final int CONNECT_TIMEOUT_MILLISECONDS = 10 * 1000;
    private static final int READ_TIMEOUT_MILLISECONDS = 10 * 1000;

    private LyricsRequests() {
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
        connection.setRequestProperty("User-Agent",
                "Morphe/" + Utils.getAppVersionName()
                        + " (" + Utils.getPatchesReleaseVersion() + ")"
                        + " https://github.com/MorpheApp/morphe-patches");
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
        connection.setRequestProperty("User-Agent",
                "Morphe/" + Utils.getAppVersionName()
                        + " (" + Utils.getPatchesReleaseVersion() + ")"
                        + " https://github.com/MorpheApp/morphe-patches");
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
}
