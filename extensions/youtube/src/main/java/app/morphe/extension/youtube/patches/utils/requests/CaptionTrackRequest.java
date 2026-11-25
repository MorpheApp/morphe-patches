/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5226
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.utils.requests;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.innertube.utils.AuthUtils;
import app.morphe.extension.shared.requests.Requester;
import app.morphe.extension.youtube.patches.CaptionCookiesPatch;
import app.morphe.extension.youtube.settings.Settings;

/**
 * Fetches the caption tracks of a video and the text of a caption track.
 */
public final class CaptionTrackRequest {

    private static final int CONNECT_TIMEOUT_MILLISECONDS = 10_000;
    private static final int READ_TIMEOUT_MILLISECONDS = 15_000;
    private static final String PLAYER_URL = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false";
    private static final String CLIENT_VERSION = "20.10.38";
    /**
     * Display time of a caption event without a duration.
     */
    private static final long DEFAULT_EVENT_DURATION_MILLISECONDS = 2_000;

    /**
     * @param url          Timedtext url of the track.
     * @param automatic    If the track is auto-generated.
     * @param translatable If the track can be machine translated with a {@code tlang} parameter.
     */
    public record Track(String url, String languageCode, boolean automatic, boolean translatable) {
        /**
         * @return If the track is an AI enhanced variant of an auto-generated track.
         */
        public boolean gemini() {
            return url.contains("variant=gemini");
        }
    }

    /**
     * @param defaultIndex Index of the default track of the default audio track, or -1 if there is none.
     */
    public record Captions(List<Track> tracks, int defaultIndex) {
        public Captions {
            tracks = List.copyOf(tracks);
        }
    }

    /**
     * A caption event, with the line breaks of its text replaced by spaces.
     */
    public record Line(long startMs, long durationMs, String text) {
    }

    private CaptionTrackRequest() {
    }

    /**
     * Must be called off the main thread.
     */
    public static Captions fetchCaptions(String videoId) throws IOException, JSONException {
        Utils.verifyOffMainThread();

        JSONObject client = new JSONObject()
                .put("clientName", "ANDROID")
                .put("clientVersion", CLIENT_VERSION);
        JSONObject body = new JSONObject()
                .put("context", new JSONObject().put("client", client))
                .put("videoId", videoId);

        HttpURLConnection connection = Requester.openConnection(PLAYER_URL);
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(CONNECT_TIMEOUT_MILLISECONDS);
        connection.setReadTimeout(READ_TIMEOUT_MILLISECONDS);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("User-Agent",
                "com.google.android.youtube/" + CLIENT_VERSION + " (Linux; U; Android 11) gzip");
        connection.setRequestProperty("X-YouTube-Client-Name", "3");
        connection.setRequestProperty("X-YouTube-Client-Version", CLIENT_VERSION);
        connection.setDoOutput(true);
        try (OutputStream output = connection.getOutputStream()) {
            output.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }

        final int responseCode = connection.getResponseCode();
        if (responseCode != Requester.HTTP_STATUS_CODE_SUCCESS) {
            connection.disconnect();
            throw new IOException("Player response code: " + responseCode);
        }

        JSONObject renderer = Requester.parseJSONObjectAndDisconnect(connection)
                .optJSONObject("captions");
        if (renderer != null) {
            renderer = renderer.optJSONObject("playerCaptionsTracklistRenderer");
        }
        if (renderer == null) {
            return new Captions(List.of(), -1);
        }

        List<Track> tracks = new ArrayList<>();
        JSONArray captionTracks = renderer.optJSONArray("captionTracks");
        if (captionTracks != null) {
            for (int i = 0, length = captionTracks.length(); i < length; i++) {
                JSONObject track = captionTracks.getJSONObject(i);
                tracks.add(new Track(
                        track.getString("baseUrl"),
                        track.getString("languageCode"),
                        "asr".equals(track.optString("kind")),
                        track.optBoolean("isTranslatable")
                ));
            }
        }

        int defaultIndex = -1;
        JSONArray audioTracks = renderer.optJSONArray("audioTracks");
        if (audioTracks != null) {
            JSONObject audioTrack = audioTracks.optJSONObject(renderer.optInt("defaultAudioTrackIndex"));
            if (audioTrack != null && audioTrack.optBoolean("hasDefaultTrack")) {
                defaultIndex = audioTrack.optInt("defaultCaptionTrackIndex", -1);
            }
        }

        return new Captions(tracks, defaultIndex < tracks.size() ? defaultIndex : -1);
    }

    /**
     * Must be called off the main thread.
     *
     * @param trackUrl Url of a {@link Track}, optionally with a {@code tlang} parameter.
     * @return Caption events with text.
     */
    public static List<Line> fetchLines(String trackUrl) throws IOException, JSONException {
        Utils.verifyOffMainThread();

        String url = trackUrl.replaceAll("&fmt=[^&]*", "") + "&fmt=json3";
        HttpURLConnection connection = Requester.openConnection(url);
        connection.setConnectTimeout(CONNECT_TIMEOUT_MILLISECONDS);
        connection.setReadTimeout(READ_TIMEOUT_MILLISECONDS);
        connection.setRequestProperty("User-Agent", CaptionCookiesPatch.getUserAgent());
        String cookies = CaptionCookiesPatch.getCookies();
        if (Settings.SET_CAPTION_COOKIES.get() && !cookies.isEmpty()) {
            connection.setRequestProperty("Cookie", cookies);
        }
        for (Map.Entry<String, String> header : AuthUtils.getRequestHeader().entrySet()) {
            if (!header.getValue().isEmpty()) {
                connection.setRequestProperty(header.getKey(), header.getValue());
            }
        }

        final int responseCode = connection.getResponseCode();
        if (responseCode != Requester.HTTP_STATUS_CODE_SUCCESS) {
            connection.disconnect();
            throw new IOException("Caption response code: " + responseCode);
        }

        List<Line> lines = new ArrayList<>();
        JSONArray events = Requester.parseJSONObjectAndDisconnect(connection).optJSONArray("events");
        if (events == null) {
            return lines;
        }

        for (int i = 0, length = events.length(); i < length; i++) {
            JSONObject event = events.getJSONObject(i);
            // Auto-generated tracks have append events that only add a line break to scroll
            // the caption window, with the timing of the line before.
            if (event.optInt("aAppend") == 1) continue;

            JSONArray segments = event.optJSONArray("segs");
            final long startMs = event.optLong("tStartMs", -1);
            if (segments == null || startMs < 0) continue;

            StringBuilder text = new StringBuilder();
            for (int j = 0, segmentsLength = segments.length(); j < segmentsLength; j++) {
                text.append(segments.getJSONObject(j).optString("utf8"));
            }
            String line = text.toString().replace('\n', ' ').trim();
            if (!line.isEmpty()) {
                lines.add(new Line(startMs,
                        event.optLong("dDurationMs", DEFAULT_EVENT_DURATION_MILLISECONDS), line));
            }
        }
        return lines;
    }
}
