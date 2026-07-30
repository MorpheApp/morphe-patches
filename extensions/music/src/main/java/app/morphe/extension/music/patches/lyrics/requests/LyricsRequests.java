/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2269
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.requests;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

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
    @NonNull
    static HttpURLConnection openConnection(@NonNull String url) throws IOException {
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

    @NonNull
    static JSONObject parseJsonObjectAndDisconnect(@NonNull HttpURLConnection connection)
            throws JSONException, IOException {
        return Requester.parseJSONObjectAndDisconnect(connection);
    }

    @NonNull
    static JSONArray parseJsonArrayAndDisconnect(@NonNull HttpURLConnection connection)
            throws JSONException, IOException {
        return Requester.parseJSONArrayAndDisconnect(connection);
    }

    static void logFailure(@NonNull String provider, @NonNull HttpURLConnection connection) {
        try {
            final int code = connection.getResponseCode();
            final String message = connection.getResponseMessage();
            Logger.printDebug(() -> provider + " request failed: " + code + " " + message);
        } catch (IOException ex) {
            Logger.printDebug(() -> provider + " request failed", ex);
        } finally {
            connection.disconnect();
        }
    }
}
