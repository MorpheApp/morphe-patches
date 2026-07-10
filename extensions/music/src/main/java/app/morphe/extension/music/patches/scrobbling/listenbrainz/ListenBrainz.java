/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.scrobbling.listenbrainz;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import app.morphe.extension.music.patches.scrobbling.ScrobbleManager;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

public class ListenBrainz {
    private static final String BASE_URL = "https://api.listenbrainz.org/";
    private static final String USER_AGENT = "YT Music Morphe (https://github.com/MorpheApp/morphe-patches)";
    
    public static class TokenValidation {
        public boolean valid;
        public String userName;
        public String message;
    }

    /**
     * Synchronously validates the provided user token.
     * Must be called from a background thread.
     */
    public static TokenValidation validateToken(String token) throws Exception {
        Utils.verifyOffMainThread();
        //noinspection ExtractMethodRecommender
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("User token is missing or blank");
        }
        URL url = new URL(BASE_URL + "1/validate-token");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent", USER_AGENT);
        conn.setRequestProperty("Authorization", "Token " + token);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);

        final int code = conn.getResponseCode();
        if (code == 200) {
            try (InputStreamReader reader = new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8)) {
                StringBuilder response = new StringBuilder();
                char[] buffer = new char[1024];
                int read;
                while ((read = reader.read(buffer)) != -1) {
                    response.append(buffer, 0, read);
                }
                JSONObject root = new JSONObject(response.toString());
                TokenValidation validation = new TokenValidation();
                validation.valid = root.optBoolean("valid");
                validation.userName = root.optString("user_name");
                validation.message = root.optString("message");
                return validation;
            }
        } else {
            TokenValidation validation = new TokenValidation();
            validation.valid = false;
            validation.message = "HTTP error " + code;
            return validation;
        }
    }

    /**
     * Submits a scrobble asynchronously on a background thread.
     */
    public static void scrobbleAsync(String artist, String track, long timestamp,
                                     String songId, String album, int duration) {
        String token = Settings.LISTENBRAINZ_USER_TOKEN.get();
        if (token.isBlank()) {
            Logger.printDebug(() -> "Cannot scrobble, token not set or invalid");
            return;
        }
        ScrobbleManager.getInstance().runOnBackgroundThread(() -> {
            try {
                JSONObject req = new JSONObject();
                req.put("listen_type", "single");

                JSONObject payload = new JSONObject();
                payload.put("listened_at", timestamp);
                payload.put("track_metadata", createTrackMetadata(artist, track, songId, album, duration));

                JSONArray payloadArray = new JSONArray();
                payloadArray.put(payload);
                req.put("payload", payloadArray);

                String jsonBody = req.toString();
                if (postRequest("1/submit-listens", token, jsonBody)) {
                    Logger.printDebug(() -> "Successfully scrobbled: '" + track + "' by: " + artist);
                }
            } catch (Exception ex) {
                Logger.printException(() -> "ListenBrainz scrobble failure", ex);
            }
        });
    }

    /**
     * Updates the Now Playing status asynchronously on a background thread.
     */
    public static void updateNowPlayingAsync(String artist, String track,
                                             String songId, String album, int duration) {
        String token = Settings.LISTENBRAINZ_USER_TOKEN.get();
        if (token.isBlank()) {
            Logger.printDebug(() -> "Cannot update Now Playing, token not set or invalid");
            return;
        }
        ScrobbleManager.getInstance().runOnBackgroundThread(() -> {
            try {
                JSONObject req = new JSONObject();
                req.put("listen_type", "playing_now");

                JSONObject payload = new JSONObject();
                payload.put("track_metadata", createTrackMetadata(artist, track, songId, album, duration));

                JSONArray payloadArray = new JSONArray();
                payloadArray.put(payload);
                req.put("payload", payloadArray);

                String jsonBody = req.toString();
                postRequest("1/submit-listens?return_msid=true", token, jsonBody);
                Logger.printDebug(() -> "Updated Now Playing status to '" + track + "'");
            } catch (Exception ex) {
                Logger.printException(() -> "Now Playing update failure", ex);
            }
        });
    }

    private static JSONObject createTrackMetadata(String artist, String track,
                                                     String songId, String album, int duration) throws Exception {
        JSONObject metadata = new JSONObject();
        metadata.put("artist_name", artist);
        metadata.put("track_name", track);
        if (album != null && !album.isBlank()) {
            metadata.put("release_name", album);
        }

        JSONObject info = new JSONObject();
        if (duration > 0) {
            info.put("duration_ms", (long) duration * 1000);
        }
        if (songId != null && !songId.isBlank()) {
            info.put("origin_url", "https://music.youtube.com/watch?v=" + songId);
        }
        info.put("submission_client", "YT Music Morphe");
        info.put("submission_client_version", "1.0.0");
        
        metadata.put("additional_info", info);
        return metadata;
    }

    private static boolean postRequest(String path, String token, String jsonBody) throws Exception {
        Utils.verifyOffMainThread();
        byte[] jsonBodyBytes = jsonBody.getBytes(StandardCharsets.UTF_8);

        //noinspection ExtractMethodRecommender
        URL url = new URL(BASE_URL + path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("User-Agent", USER_AGENT);
        conn.setRequestProperty("Authorization", "Token " + token);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setFixedLengthStreamingMode(jsonBodyBytes.length);
        conn.setDoOutput(true);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(jsonBodyBytes);
        }

        final int code = conn.getResponseCode();
        if (code == 200) {
            return true;
        }
        Logger.printException(() -> "ListenBrainz server returned code: " + code);
        return false;
    }
}
