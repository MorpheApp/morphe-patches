/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2763
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.utils.requests;

import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.requests.Requester;

/**
 * Submits a channel to the AiSList community list, which the AI channel filter reads back
 * from its published blocklist and warnlist.
 */
public final class AiSListSubmitRequest {

    /**
     * The Android client player response has no microformat, so the web client is used
     * because it is the only one returning the owner profile url with the channel handle.
     */
    private static final String PLAYER_URL = "https://www.youtube.com/youtubei/v1/player"
            + "?prettyPrint=false&fields=microformat.playerMicroformatRenderer.ownerProfileUrl";
    private static final String WEB_CLIENT_VERSION = "2.20250101.00.00";

    private static final String SUBMIT_URL = "https://api.aisloplist.com";

    private static final int TIMEOUT_MS = 10 * 1000;
    private static final int HTTP_TOO_MANY_REQUESTS = 429;

    /**
     * Longest handle the API accepts, including the leading '@'.
     */
    private static final int MAX_HANDLE_LENGTH = 31;

    private static final String SUBMIT_FAILED_KEY = "morphe_aislist_submit_failed";

    private AiSListSubmitRequest() {
    }

    private static String createPlayerBody(String videoId) throws JSONException {
        JSONObject client = new JSONObject();
        client.put("clientName", "WEB");
        client.put("clientVersion", WEB_CLIENT_VERSION);
        Locale locale = Locale.getDefault();
        client.put("hl", locale.getLanguage());
        client.put("gl", locale.getCountry());

        JSONObject context = new JSONObject();
        context.put("client", client);

        JSONObject body = new JSONObject();
        body.put("context", context);
        body.put("contentCheckOk", true);
        body.put("racyCheckOk", true);
        body.put("videoId", videoId);

        return body.toString();
    }

    /**
     * @return The '@handle' of the channel that published the video, or null if it cannot be found.
     */
    @Nullable
    public static String fetchChannelHandle(String videoId) {
        Utils.verifyOffMainThread();

        HttpURLConnection connection = null;
        try {
            connection = openPostConnection(PLAYER_URL);
            connection.setRequestProperty("X-YouTube-Client-Name", "1");
            connection.setRequestProperty("X-YouTube-Client-Version", WEB_CLIENT_VERSION);
            writeBody(connection, createPlayerBody(videoId));

            final int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                Logger.printDebug(() -> "Channel handle request failed with code: " + responseCode);
                return null;
            }

            JSONObject json = Requester.parseJSONObject(connection);
            String profileUrl = json.getJSONObject("microformat")
                    .getJSONObject("playerMicroformatRenderer")
                    .getString("ownerProfileUrl");

            final int handleIndex = profileUrl.indexOf('@');
            if (handleIndex < 0) {
                Logger.printDebug(() -> "Owner profile url has no handle: " + profileUrl);
                return null;
            }
            String handle = profileUrl.substring(handleIndex);

            return isValidHandle(handle) ? handle : null;
        } catch (Exception ex) {
            Logger.printInfo(() -> "fetchChannelHandle failed for video: " + videoId, ex);
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    /**
     * Sends the submission. The channel is added to a list only after the maintainers review it.
     *
     * @return The string key of the message to show for the outcome.
     */
    public static String submit(String handle, String videoUrl, String username) {
        Utils.verifyOffMainThread();

        HttpURLConnection connection = null;
        try {
            JSONObject body = new JSONObject();
            body.put("channel_handle", handle);
            body.put("video_url", videoUrl);
            body.put("username", username);

            connection = openPostConnection(SUBMIT_URL);
            writeBody(connection, body.toString());

            final int responseCode = connection.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                return "morphe_aislist_submit_success";
            }

            String error = readErrorResponse(connection);
            Logger.printDebug(() -> "Submit failed with code: " + responseCode
                    + " response: " + error);

            return switch (responseCode) {
                case HttpURLConnection.HTTP_CONFLICT -> "morphe_aislist_submit_failed_conflict";
                case HTTP_TOO_MANY_REQUESTS -> "morphe_aislist_submit_failed_rate_limit";
                case HttpURLConnection.HTTP_BAD_REQUEST -> "morphe_aislist_submit_failed_rejected";
                default -> SUBMIT_FAILED_KEY;
            };
        } catch (Exception ex) {
            Logger.printInfo(() -> "submit failed for channel: " + handle, ex);
            return SUBMIT_FAILED_KEY;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    /**
     * Mirrors the field rules of the API, which no longer rejects malformed handles itself.
     */
    private static boolean isValidHandle(String handle) {
        if (handle.length() < 4 || handle.length() > MAX_HANDLE_LENGTH) {
            return false;
        }

        for (int i = 1, length = handle.length(); i < length; i++) {
            final char c = handle.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '_' && c != '-' && c != '.') {
                return false;
            }
        }

        return true;
    }

    private static HttpURLConnection openPostConnection(String url) throws Exception {
        HttpURLConnection connection = Requester.openConnection(url);
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setUseCaches(false);
        connection.setDoOutput(true);
        connection.setConnectTimeout(TIMEOUT_MS);
        connection.setReadTimeout(TIMEOUT_MS);
        return connection;
    }

    private static void writeBody(HttpURLConnection connection, String body) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(bytes.length);
        connection.getOutputStream().write(bytes);
    }

    /**
     * The API describes its errors in a plain text body, which is only used for logging.
     */
    private static String readErrorResponse(HttpURLConnection connection) {
        try {
            return Requester.parseErrorString(connection);
        } catch (Exception ex) {
            return "";
        }
    }
}
