/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.scrobbling.lastfm;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import app.morphe.extension.music.patches.scrobbling.ScrobbleManager;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.requests.Requester;

public class LastFM {
    private static final String BASE_URL = "https://ws.audioscrobbler.com/2.0/";
    private static final String USER_AGENT = "Morphe/" + Utils.getPatchesReleaseVersion() + " (YTMusic/" + Utils.getAppVersionName() + ")";

    public static final String API_KEY = "986d00852eea80eda8b2930e0abf5c46";
    public static final String SECRET = "1d802c749ccec53103400582fcaebd01";

    private static final Map<String, CacheEntry> albumCache = new HashMap<>();
    private static final long CACHE_HIT_TTL = 24 * 60 * 60 * 1000L;
    private static final long CACHE_MISS_TTL = 60 * 60 * 1000L;

    private static class CacheEntry {
        final String album;
        final long expiry;
        CacheEntry(String album, long expiry) {
            this.album = album;
            this.expiry = expiry;
        }
    }

    public static class Session {
        public String name;
        public String key;
        public int subscriber;
    }

    public static class LastFMApiException extends Exception {
        private final int errorCode;

        public LastFMApiException(String message, int errorCode) {
            super(message + " (Code: " + errorCode + ")");
            this.errorCode = errorCode;
        }

        public int getErrorCode() {
            return errorCode;
        }
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

    @SuppressWarnings("CharsetObjectCanBeUsed")
    private static String executePostRequest(Map<String, String> params) throws Exception {
        String method = params.get("method");
        Logger.printDebug(() -> "Last.fm: Executing API call for method: " + method);

        Map<String, String> paramsForSig = new HashMap<>(params);
        String apiSig = calculateApiSig(paramsForSig);

        StringBuilder postData = new StringBuilder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            //noinspection SizeReplaceableByIsEmpty
            if (postData.length() != 0) postData.append('&');
            postData.append(URLEncoder.encode(entry.getKey(), "UTF-8"));
            postData.append('=');
            postData.append(URLEncoder.encode(entry.getValue(), "UTF-8"));
        }
        postData.append('&').append(URLEncoder.encode("api_sig", "UTF-8")).append('=').append(URLEncoder.encode(apiSig, "UTF-8"));
        postData.append('&').append(URLEncoder.encode("format", "UTF-8")).append('=').append(URLEncoder.encode("json", "UTF-8"));

        byte[] postDataBytes = postData.toString().getBytes(StandardCharsets.UTF_8);

        HttpURLConnection conn = Requester.openConnection(BASE_URL);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("User-Agent", USER_AGENT);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        conn.setRequestProperty("Content-Length", String.valueOf(postDataBytes.length));
        conn.setFixedLengthStreamingMode(postDataBytes.length);
        conn.setDoOutput(true);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(postDataBytes);
        }

        int code = conn.getResponseCode();
        Logger.printDebug(() -> "Last.fm: Response code: " + code + " for method: " + method);

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
                Logger.printInfo(() -> "Last.fm: API error response: " + errResponse + " (HTTP Code: " + code + ")");
                try {
                    JSONObject errorObj = new JSONObject(errResponse);
                    if (errorObj.has("error")) {
                        throw new LastFMApiException(errorObj.optString("message", "API Error"), errorObj.getInt("error"));
                    }
                } catch (LastFMApiException e) {
                    throw e;
                } catch (Exception ignored) {}
                throw new Exception("HTTP error " + code + ": " + errResponse);
            }
        }
    }

    public static String fetchAlbum(String artist, String track) {
        if (!Settings.SCROBBLING_GUESS_ALBUM.get()) return null;
        if (artist == null || artist.isBlank() || track == null || track.isBlank()) return null;
        String key = artist.toLowerCase() + "\u0000" + track.toLowerCase();
        synchronized (albumCache) {
            CacheEntry cached = albumCache.get(key);
            if (cached != null && System.currentTimeMillis() < cached.expiry) {
                if (cached.album == null || cached.album.isEmpty()) return null;
                return cached.album;
            }
        }
        String fetched = null;
        try {
            fetched = fetchAlbumNetwork(artist, track);
        } catch (Exception ignored) {
            fetched = null;
        }
        synchronized (albumCache) {
            if (albumCache.size() > 200) albumCache.clear();
            long ttl = fetched != null ? CACHE_HIT_TTL : CACHE_MISS_TTL;
            albumCache.put(key, new CacheEntry(fetched != null ? fetched : "", System.currentTimeMillis() + ttl));
        }
        return fetched;
    }

    private static String fetchAlbumNetwork(String artist, String track) throws Exception {
        String url = BASE_URL + "?method=track.getInfo&api_key=" + URLEncoder.encode(API_KEY, "UTF-8")
                + "&artist=" + URLEncoder.encode(artist, "UTF-8")
                + "&track=" + URLEncoder.encode(track, "UTF-8")
                + "&autocorrect=1&format=json";
        HttpURLConnection conn = Requester.openConnection(url);
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent", USER_AGENT);
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        int code = conn.getResponseCode();
        if (code < 200 || code >= 300) {
            conn.disconnect();
            return null;
        }
        String response;
        try (InputStreamReader reader = new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8)) {
            StringBuilder sb = new StringBuilder();
            char[] buffer = new char[1024];
            int read;
            while ((read = reader.read(buffer)) != -1) sb.append(buffer, 0, read);
            response = sb.toString();
        } finally {
            conn.disconnect();
        }
        JSONObject root = new JSONObject(response);
        if (root.has("error")) return null;
        if (!root.has("track")) return null;
        JSONObject trackObj = root.getJSONObject("track");
        JSONObject albumObj = trackObj.optJSONObject("album");
        if (albumObj == null) return null;
        String title = albumObj.optString("title", "");
        if (title == null || title.isBlank()) return null;
        return title.trim();
    }

    public static Session getMobileSession(String username, String password) throws Exception {
        Map<String, String> params = new HashMap<>();
        params.put("method", "auth.getMobileSession");
        params.put("api_key", API_KEY);
        params.put("username", username);
        params.put("password", password);

        String jsonResponse = executePostRequest(params);
        JSONObject root = new JSONObject(jsonResponse);
        if (root.has("session")) {
            JSONObject sessionJson = root.getJSONObject("session");
            Session session = new Session();
            session.name = sessionJson.getString("name");
            session.key = sessionJson.getString("key");
            session.subscriber = sessionJson.optInt("subscriber");
            return session;
        }
        throw new Exception("Invalid response structure from Last.fm");
    }

    public static void updateNowPlaying(String sessionKey, String artist, String track, String album, Integer duration) {
        if (sessionKey == null || sessionKey.isBlank()) return;
        ScrobbleManager.getInstance().runOnBackgroundThread(() -> {
            try {
                String effectiveAlbum = album;
                if ((effectiveAlbum == null || effectiveAlbum.isBlank()) && Settings.SCROBBLING_GUESS_ALBUM.get()) {
                    try {
                        String guessed = fetchAlbum(artist, track);
                        if (guessed != null && !guessed.isBlank()) effectiveAlbum = guessed;
                    } catch (Exception ignored) {}
                }
                Map<String, String> params = new HashMap<>();
                params.put("method", "track.updateNowPlaying");
                params.put("api_key", API_KEY);
                params.put("sk", sessionKey);
                params.put("artist", artist);
                params.put("track", track);
                if (effectiveAlbum != null && !effectiveAlbum.isBlank()) params.put("album", effectiveAlbum);
                if (duration != null && duration > 0) params.put("duration", String.valueOf(duration));

                executePostRequest(params);
                Logger.printDebug(() -> "Updated Now Playing for " + artist + " - " + track);
            } catch (LastFMApiException ex) {
                Logger.printException(() -> "Last.fm API error during updateNowPlaying", ex);
                if (ex.getErrorCode() == 9) {
                    Logger.printInfo(() -> "Last.fm: Session key is invalid. Logging out.");
                    Settings.LASTFM_SESSION_KEY.resetToDefault();
                    Settings.LASTFM_USERNAME.resetToDefault();
                }
            } catch (Exception ex) {
                Logger.printException(() -> "Failed to update Now Playing", ex);
            }
        });
    }

    private static void logScrobbleResponse(String jsonResponse, String artist, String track) {
        try {
            JSONObject root = new JSONObject(jsonResponse);
            if (root.has("scrobbles")) {
                JSONObject scrobbles = root.getJSONObject("scrobbles");
                JSONObject attr = scrobbles.optJSONObject("@attr");
                @SuppressWarnings("unused")
                int accepted = attr != null ? attr.optInt("accepted", 0) : 0;
                int ignored = attr != null ? attr.optInt("ignored", 0) : 0;

                if (ignored > 0) {
                    String ignoredMsg = "Unknown reason";
                    if (scrobbles.has("scrobble")) {
                        Object scrobbleObj = scrobbles.get("scrobble");
                        JSONObject scrobble = null;
                        if (scrobbleObj instanceof JSONObject) {
                            scrobble = (JSONObject) scrobbleObj;
                        } else if (scrobbleObj instanceof JSONArray scrobbleArray) {
                            if (scrobbleArray.length() > 0) {
                                scrobble = scrobbleArray.optJSONObject(0);
                            }
                        }
                        if (scrobble != null && scrobble.has("ignoredMessage")) {
                            JSONObject msgObj = scrobble.getJSONObject("ignoredMessage");
                            ignoredMsg = msgObj.optString("#text", "Code " + msgObj.optString("code"));
                        }
                    }
                    final String finalIgnoredMsg = ignoredMsg;
                    Logger.printInfo(() -> "Last.fm: Scrobble ignored for " + artist + " - " + track + ". Reason: " + finalIgnoredMsg);
                } else {
                    Logger.printDebug(() -> "Last.fm: Scrobbled track: " + artist + " - " + track + " (accepted)");
                }
            } else {
                Logger.printDebug(() -> "Last.fm: Scrobbled track (raw response): " + jsonResponse);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "Last.fm: Failed to parse scrobble response: " + jsonResponse, ex);
        }
    }

    public static void scrobble(String sessionKey, String artist, String track, String album, Integer duration, long timestamp) {
        if (sessionKey == null || sessionKey.isBlank()) return;
        ScrobbleManager.getInstance().runOnBackgroundThread(() -> {
            try {
                String effectiveAlbum = album;
                if ((effectiveAlbum == null || effectiveAlbum.isBlank()) && Settings.SCROBBLING_GUESS_ALBUM.get()) {
                    try {
                        String guessed = fetchAlbum(artist, track);
                        if (guessed != null && !guessed.isBlank()) effectiveAlbum = guessed;
                    } catch (Exception ignored) {}
                }
                Map<String, String> params = new HashMap<>();
                params.put("method", "track.scrobble");
                params.put("api_key", API_KEY);
                params.put("sk", sessionKey);
                params.put("artist[0]", artist);
                params.put("track[0]", track);
                params.put("timestamp[0]", String.valueOf(timestamp));
                if (effectiveAlbum != null && !effectiveAlbum.isBlank()) params.put("album[0]", effectiveAlbum);
                if (duration != null && duration > 0) params.put("duration[0]", String.valueOf(duration));

                String response = executePostRequest(params);
                logScrobbleResponse(response, artist, track);
            } catch (LastFMApiException ex) {
                Logger.printException(() -> "Last.fm API error during scrobble", ex);
                if (ex.getErrorCode() == 9) {
                    Logger.printInfo(() -> "Last.fm: Session key is invalid. Logging out.");
                    Settings.LASTFM_SESSION_KEY.resetToDefault();
                    Settings.LASTFM_USERNAME.resetToDefault();
                }
            } catch (Exception ex) {
                Logger.printException(() -> "Failed to scrobble track", ex);
            }
        });
    }

    public static void love(String sessionKey, String artist, String track) {
        if (sessionKey == null || sessionKey.isBlank()) return;
        ScrobbleManager.getInstance().runOnBackgroundThread(() -> {
            try {
                Map<String, String> params = new HashMap<>();
                params.put("method", "track.love");
                params.put("api_key", API_KEY);
                params.put("sk", sessionKey);
                params.put("artist", artist);
                params.put("track", track);

                executePostRequest(params);
                Logger.printInfo(() -> "Last.fm: Loved track: " + artist + " - " + track);
            } catch (LastFMApiException ex) {
                Logger.printException(() -> "Last.fm API error during love", ex);
                if (ex.getErrorCode() == 9) {
                    Logger.printInfo(() -> "Last.fm: Session key is invalid. Logging out.");
                    Settings.LASTFM_SESSION_KEY.resetToDefault();
                    Settings.LASTFM_USERNAME.resetToDefault();
                }
            } catch (Exception e) {
                Logger.printException(() -> "Last.fm: Failed to love track", e);
            }
        });
    }

    public static void unlove(String sessionKey, String artist, String track) {
        if (sessionKey == null || sessionKey.isBlank()) return;
        ScrobbleManager.getInstance().runOnBackgroundThread(() -> {
            try {
                Map<String, String> params = new HashMap<>();
                params.put("method", "track.unlove");
                params.put("api_key", API_KEY);
                params.put("sk", sessionKey);
                params.put("artist", artist);
                params.put("track", track);

                executePostRequest(params);
                Logger.printInfo(() -> "Last.fm: Unloved track: " + artist + " - " + track);
            } catch (LastFMApiException ex) {
                Logger.printException(() -> "Last.fm API error during unlove", ex);
                if (ex.getErrorCode() == 9) {
                    Logger.printInfo(() -> "Last.fm: Session key is invalid. Logging out.");
                    Settings.LASTFM_SESSION_KEY.resetToDefault();
                    Settings.LASTFM_USERNAME.resetToDefault();
                }
            } catch (Exception e) {
                Logger.printException(() -> "Last.fm: Failed to unlove track", e);
            }
        });
    }
}
