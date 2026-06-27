/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */
package app.morphe.extension.music.patches.lastfm;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import app.morphe.extension.shared.Logger;

public class LastFM {
    private static final String BASE_URL = "https://ws.audioscrobbler.com/2.0/";
    private static final String USER_AGENT = "YT Music Morphe (https://github.com/MorpheApp/morphe-patches)";
    
    public static final String API_KEY = "986d00852eea80eda8b2930e0abf5c46";
    public static final String SECRET = "1d802c749ccec53103400582fcaebd01";

    private static final Gson gson = new Gson();
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();

    public static class Session {
        public String name;
        public String key;
        public int subscriber;
    }

    public static class MobileSessionResponse {
        public Session session;
    }

    public static class LastFmError {
        public int error;
        public String message;
    }

    private static String calculateApiSig(Map<String, String> params) {
        List<String> sortedKeys = new ArrayList<>(params.keySet());
        Collections.sort(sortedKeys);
        StringBuilder sb = new StringBuilder();
        for (String key : sortedKeys) {
            sb.append(key).append(params.get(key));
        }
        sb.append(SECRET);
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : digest) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            throw new RuntimeException("MD5 failed", e);
        }
    }

    private static String executePostRequest(Map<String, String> params) throws Exception {
        Map<String, String> paramsForSig = new HashMap<>(params);
        String apiSig = calculateApiSig(paramsForSig);

        StringBuilder postData = new StringBuilder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (postData.length() != 0) postData.append('&');
            postData.append(URLEncoder.encode(entry.getKey(), "UTF-8"));
            postData.append('=');
            postData.append(URLEncoder.encode(entry.getValue(), "UTF-8"));
        }
        postData.append('&').append(URLEncoder.encode("api_sig", "UTF-8")).append('=').append(URLEncoder.encode(apiSig, "UTF-8"));
        postData.append('&').append(URLEncoder.encode("format", "UTF-8")).append('=').append(URLEncoder.encode("json", "UTF-8"));

        byte[] postDataBytes = postData.toString().getBytes(StandardCharsets.UTF_8);

        URL url = new URL(BASE_URL);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("User-Agent", USER_AGENT);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        conn.setRequestProperty("Content-Length", String.valueOf(postDataBytes.length));
        conn.setDoOutput(true);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(postDataBytes);
        }

        int code = conn.getResponseCode();
        if (code >= 200 && code < 300) {
            try (InputStreamReader reader = new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8)) {
                StringBuilder response = new StringBuilder();
                char[] buffer = new char[1024];
                int read;
                while ((read = reader.read(buffer)) != -1) {
                    response.append(buffer, 0, read);
                }
                return response.toString();
            }
        } else {
            try (InputStreamReader reader = new InputStreamReader(conn.getErrorStream() != null ? conn.getErrorStream() : conn.getInputStream(), StandardCharsets.UTF_8)) {
                StringBuilder response = new StringBuilder();
                char[] buffer = new char[1024];
                int read;
                while ((read = reader.read(buffer)) != -1) {
                    response.append(buffer, 0, read);
                }
                String errResponse = response.toString();
                try {
                    LastFmError errorObj = gson.fromJson(errResponse, LastFmError.class);
                    if (errorObj != null && errorObj.message != null) {
                        throw new Exception(errorObj.message + " (Code: " + errorObj.error + ")");
                    }
                } catch (Exception ignored) {}
                throw new Exception("HTTP error " + code + ": " + errResponse);
            }
        }
    }

    public static Session getMobileSession(String username, String password) throws Exception {
        Map<String, String> params = new HashMap<>();
        params.put("method", "auth.getMobileSession");
        params.put("api_key", API_KEY);
        params.put("username", username);
        params.put("password", password);

        String jsonResponse = executePostRequest(params);
        MobileSessionResponse response = gson.fromJson(jsonResponse, MobileSessionResponse.class);
        if (response != null && response.session != null) {
            return response.session;
        }
        throw new Exception("Invalid response structure from Last.fm");
    }

    public static void updateNowPlaying(String sessionKey, String artist, String track, String album, Integer duration) {
        if (sessionKey == null || sessionKey.trim().isEmpty()) return;
        executor.submit(() -> {
            try {
                Map<String, String> params = new HashMap<>();
                params.put("method", "track.updateNowPlaying");
                params.put("api_key", API_KEY);
                params.put("sk", sessionKey);
                params.put("artist", artist);
                params.put("track", track);
                if (album != null && !album.trim().isEmpty()) params.put("album", album);
                if (duration != null && duration > 0) params.put("duration", String.valueOf(duration));

                executePostRequest(params);
                Logger.printInfo(() -> "Last.fm: Updated Now Playing for " + artist + " - " + track);
            } catch (Exception e) {
                Logger.printException(() -> "Last.fm: Failed to update Now Playing", e);
            }
        });
    }

    public static void scrobble(String sessionKey, String artist, String track, String album, Integer duration, long timestamp) {
        if (sessionKey == null || sessionKey.trim().isEmpty()) return;
        executor.submit(() -> {
            try {
                Map<String, String> params = new HashMap<>();
                params.put("method", "track.scrobble");
                params.put("api_key", API_KEY);
                params.put("sk", sessionKey);
                params.put("artist[0]", artist);
                params.put("track[0]", track);
                params.put("timestamp[0]", String.valueOf(timestamp));
                if (album != null && !album.trim().isEmpty()) params.put("album[0]", album);
                if (duration != null && duration > 0) params.put("duration[0]", String.valueOf(duration));

                executePostRequest(params);
                Logger.printInfo(() -> "Last.fm: Scrobbled track: " + artist + " - " + track);
            } catch (Exception e) {
                Logger.printException(() -> "Last.fm: Failed to scrobble track", e);
            }
        });
    }
}
