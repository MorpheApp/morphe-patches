/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2269
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.requests;

import java.io.IOException;
import java.net.HttpURLConnection;

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
