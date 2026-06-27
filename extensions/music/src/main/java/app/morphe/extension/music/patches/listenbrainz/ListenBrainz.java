/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.extension.music.patches.listenbrainz;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import app.morphe.extension.shared.Logger;

public class ListenBrainz {
    private static final String BASE_URL = "https://api.listenbrainz.org/";
    private static final String USER_AGENT = "YT Music Morphe (https://github.com/MorpheApp/morphe-patches)";
    
    public static final float DEFAULT_SCROBBLE_DELAY_PERCENT = 0.5f;
    public static final int DEFAULT_SCROBBLE_MIN_SONG_DURATION = 30;
    public static final int DEFAULT_SCROBBLE_DELAY_SECONDS = 180;

    private static final Gson gson = new Gson();
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();

    public static class TokenValidation {
        public boolean valid;
        @SerializedName("user_name")
        public String userName;
        public String message;
    }

    public static class AdditionalInfo {
        @SerializedName("duration_ms")
        public Long durationMs;
        @SerializedName("origin_url")
        public String originUrl;
        @SerializedName("submission_client")
        public String submissionClient = "YT Music Morphe";
        @SerializedName("submission_client_version")
        public String submissionClientVersion = "1.0.0";
    }

    public static class TrackMetadata {
        @SerializedName("artist_name")
        public String artistName;
        @SerializedName("track_name")
        public String trackName;
        @SerializedName("release_name")
        public String releaseName;
        @SerializedName("additional_info")
        public AdditionalInfo additionalInfo;
    }

    public static class ListenPayload {
        @SerializedName("listened_at")
        public Long listenedAt;
        @SerializedName("track_metadata")
        public TrackMetadata trackMetadata;
    }

    public static class SubmitListensRequest {
        @SerializedName("listen_type")
        public String listenType;
        public List<ListenPayload> payload;
    }

    /**
     * Synchronously validates the provided user token.
     * Must be called from a background thread.
     */
    public static TokenValidation validateToken(String token) throws Exception {
        if (token == null || token.trim().isEmpty()) {
            throw new IllegalArgumentException("User token is missing or blank");
        }
        URL url = new URL(BASE_URL + "1/validate-token");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent", USER_AGENT);
        conn.setRequestProperty("Authorization", "Token " + token);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);

        int code = conn.getResponseCode();
        if (code == 200) {
            try (InputStreamReader reader = new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8)) {
                return gson.fromJson(reader, TokenValidation.class);
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
    public static void scrobbleAsync(final String artist, final String track, final long timestamp,
                                     final String songId, final String album, final int duration) {
        final String token = ListenBrainzTokenStore.retrieve();
        if (token == null || token.trim().isEmpty()) {
            Logger.printInfo(() -> "ListenBrainz: cannot scrobble, token not set or invalid");
            return;
        }
        executor.submit(() -> {
            try {
                SubmitListensRequest req = new SubmitListensRequest();
                req.listenType = "single";
                
                ListenPayload payload = new ListenPayload();
                payload.listenedAt = timestamp;
                payload.trackMetadata = createTrackMetadata(artist, track, songId, album, duration);
                req.payload = Collections.singletonList(payload);

                String jsonBody = gson.toJson(req);
                postRequest("1/submit-listens", token, jsonBody);
                Logger.printInfo(() -> "ListenBrainz: successfully scrobbled '" + track + "' by " + artist);
            } catch (Exception e) {
                Logger.printException(() -> "ListenBrainz scrobble failed", e);
            }
        });
    }

    /**
     * Updates the Now Playing status asynchronously on a background thread.
     */
    public static void updateNowPlayingAsync(final String artist, final String track,
                                             final String songId, final String album, final int duration) {
        final String token = ListenBrainzTokenStore.retrieve();
        if (token == null || token.trim().isEmpty()) {
            Logger.printInfo(() -> "ListenBrainz: cannot update Now Playing, token not set or invalid");
            return;
        }
        executor.submit(() -> {
            try {
                SubmitListensRequest req = new SubmitListensRequest();
                req.listenType = "playing_now";
                
                ListenPayload payload = new ListenPayload();
                payload.trackMetadata = createTrackMetadata(artist, track, songId, album, duration);
                req.payload = Collections.singletonList(payload);

                String jsonBody = gson.toJson(req);
                postRequest("1/submit-listens?return_msid=true", token, jsonBody);
                Logger.printInfo(() -> "ListenBrainz: updated Now Playing status to '" + track + "'");
            } catch (Exception e) {
                Logger.printException(() -> "ListenBrainz Now Playing update failed", e);
            }
        });
    }

    private static TrackMetadata createTrackMetadata(String artist, String track, String songId, String album, int duration) {
        TrackMetadata metadata = new TrackMetadata();
        metadata.artistName = artist;
        metadata.trackName = track;
        metadata.releaseName = (album != null && !album.trim().isEmpty()) ? album : null;

        AdditionalInfo info = new AdditionalInfo();
        if (duration > 0) {
            info.durationMs = (long) duration * 1000;
        }
        if (songId != null && !songId.trim().isEmpty()) {
            info.originUrl = "https://music.youtube.com/watch?v=" + songId;
        }
        metadata.additionalInfo = info;
        return metadata;
    }

    private static void postRequest(String path, String token, String jsonBody) throws Exception {
        URL url = new URL(BASE_URL + path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("User-Agent", USER_AGENT);
        conn.setRequestProperty("Authorization", "Token " + token);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
        }

        int code = conn.getResponseCode();
        if (code != 200) {
            throw new Exception("ListenBrainz server returned code: " + code);
        }
    }
}
