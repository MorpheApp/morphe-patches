/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3883
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.utils.requests;

import android.net.Uri;

import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.innertube.utils.AuthUtils;
import app.morphe.extension.shared.requests.Requester;

/**
 * Adds a video to the watch history of the account, as if it was watched to the end,
 * the same way as the {@code --mark-watched} option of yt-dlp.
 */
public final class MarkWatchedRequest {

    /**
     * Characters of the client playback nonce, which identifies a playback in the reports.
     */
    private static final String CPN_ALPHABET =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_";

    private static final int CPN_LENGTH = 16;

    private static final int CONNECTION_TIMEOUT_MILLISECONDS = 10 * 1000;

    private static final SecureRandom random = new SecureRandom();

    private MarkWatchedRequest() {
    }

    public static void markWatched(String videoId) {
        Objects.requireNonNull(videoId);
        if (AuthUtils.isNotLoggedIn()) {
            Logger.printDebug(() -> "Not logged in, not marking as watched: " + videoId);
            return;
        }
        // The headers of the account that is signed in now, not when the request runs.
        Map<String, String> authHeaders = AuthUtils.getRequestHeader();
        Utils.runOnBackgroundThread(() -> sendRequest(videoId, authHeaders));
    }

    private static void sendRequest(String videoId, Map<String, String> authHeaders) {
        Utils.verifyOffMainThread();

        try {
            byte[] requestBody = PlaylistRoutes.playbackTrackingBody(videoId);
            HttpURLConnection connection = PlaylistRoutes.getConnection(
                    PlaylistRoutes.GET_PLAYBACK_TRACKING, authHeaders);
            connection.setFixedLengthStreamingMode(requestBody.length);
            connection.getOutputStream().write(requestBody);

            final int responseCode = connection.getResponseCode();
            if (responseCode != Requester.HTTP_STATUS_CODE_SUCCESS) {
                Logger.printInfo(() -> "Playback tracking failed with code: " + responseCode);
                return;
            }

            JSONObject playbackTracking = Requester.parseJSONObjectAndDisconnect(connection)
                    .optJSONObject("playbackTracking");
            if (playbackTracking == null) {
                Logger.printInfo(() -> "No playback tracking: " + videoId);
                return;
            }

            // The playback report starts the playback, the watch time report adds it to the watch history.
            if (sendReport(getBaseUrl(playbackTracking, "videostatsPlaybackUrl"), false, authHeaders)
                    && sendReport(getBaseUrl(playbackTracking, "videostatsWatchtimeUrl"), true, authHeaders)) {
                Logger.printDebug(() -> "Marked as watched: " + videoId);
            }
        } catch (Exception ex) {
            Logger.printInfo(() -> "Mark as watched failed: " + videoId, ex);
        }
    }

    @Nullable
    private static String getBaseUrl(JSONObject playbackTracking, String key) {
        JSONObject url = playbackTracking.optJSONObject(key);
        return url == null ? null : url.optString("baseUrl", null);
    }

    /**
     * @param isWatchTime If the report is the watch time report, which needs the watched range.
     * @return If the report was sent.
     */
    private static boolean sendReport(@Nullable String baseUrl, boolean isWatchTime,
                                      Map<String, String> authHeaders) throws Exception {
        if (baseUrl == null || baseUrl.isEmpty()) {
            Logger.printInfo(() -> "No report url, watch time: " + isWatchTime);
            return false;
        }

        Uri uri = Uri.parse(baseUrl);
        Map<String, String> parameters = new LinkedHashMap<>();
        for (String name : uri.getQueryParameterNames()) {
            parameters.put(name, uri.getQueryParameter(name));
        }

        // Right before the end of the video.
        double length = 1.5;
        try {
            String lengthParameter = parameters.get("len");
            if (lengthParameter != null) {
                length = Double.parseDouble(lengthParameter);
            }
        } catch (NumberFormatException ignored) {
        }
        String position = String.valueOf(Math.max(0, length - 1));

        parameters.put("ver", "2");
        parameters.put("cpn", createCpn());
        parameters.put("cmt", position);
        // Otherwise the playback is reported as a Short.
        parameters.put("el", "detailpage");
        if (isWatchTime) {
            parameters.put("st", "0");
            parameters.put("et", position);
        }

        Uri.Builder builder = uri.buildUpon().clearQuery();
        for (Map.Entry<String, String> parameter : parameters.entrySet()) {
            builder.appendQueryParameter(parameter.getKey(), parameter.getValue());
        }

        HttpURLConnection connection = Requester.openConnection(builder.build().toString());
        try {
            connection.setRequestProperty("User-Agent", PlaylistRoutes.getUserAgent());
            for (Map.Entry<String, String> header : authHeaders.entrySet()) {
                String value = header.getValue();
                if (value != null && !value.isEmpty()) {
                    connection.setRequestProperty(header.getKey(), value);
                }
            }
            connection.setUseCaches(false);
            connection.setConnectTimeout(CONNECTION_TIMEOUT_MILLISECONDS);
            connection.setReadTimeout(CONNECTION_TIMEOUT_MILLISECONDS);

            final int responseCode = connection.getResponseCode();
            if (responseCode / 100 != 2) {
                Logger.printInfo(() -> "Report failed with code: " + responseCode + ", watch time: " + isWatchTime);
                return false;
            }
            return true;
        } finally {
            connection.disconnect();
        }
    }

    private static String createCpn() {
        StringBuilder cpn = new StringBuilder(CPN_LENGTH);
        for (int i = 0; i < CPN_LENGTH; i++) {
            cpn.append(CPN_ALPHABET.charAt(random.nextInt(256) & 63));
        }
        return cpn.toString();
    }
}
