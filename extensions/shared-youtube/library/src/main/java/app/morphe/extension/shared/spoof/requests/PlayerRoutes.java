/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 */
package app.morphe.extension.shared.spoof.requests;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.Locale;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.requests.Requester;
import app.morphe.extension.shared.requests.Route;
import app.morphe.extension.shared.settings.AppLanguage;
import app.morphe.extension.shared.spoof.ClientType;
import app.morphe.extension.shared.spoof.SpoofVideoStreamsPatch;
import app.morphe.extension.shared.spoof.js.JavaScriptManager;

final class PlayerRoutes {
    static final Route.CompiledRoute GET_PLAYER_STREAMING_DATA = new Route(
            Route.Method.POST,
            "player" +
                    "?fields=playabilityStatus,streamingData" +
                    "&alt=proto"
    ).compile();

    static final Route.CompiledRoute GET_REEL_STREAMING_DATA = new Route(
            Route.Method.POST,
            "reel/reel_item_watch" +
                    "?fields=playerResponse.playabilityStatus,playerResponse.streamingData" +
                    "&alt=proto"
    ).compile();

    private static final String YT_API_URL = "https://youtubei.googleapis.com/youtubei/v1/";

    /**
     * TCP connection and HTTP read timeout
     */
    private static final int CONNECTION_TIMEOUT_MILLISECONDS = 10 * 1000; // 10 Seconds.

    private PlayerRoutes() {
    }

    static String createInnertubeBody(ClientType clientType, String videoId) {
        JSONObject innerTubeBody = new JSONObject();

        try {
            JSONObject context = new JSONObject();

            AppLanguage language = SpoofVideoStreamsPatch.getLanguageOverride();
            if (language == null) {
                // Force original audio has not overridden the language.
                language = AppLanguage.DEFAULT;
            }
            Locale streamLocale = language.getLocale();

            JSONObject client = new JSONObject();
            client.put("deviceMake", clientType.deviceMake);
            client.put("deviceModel", clientType.deviceModel);
            client.put("clientName", clientType.clientName);
            client.put("clientVersion", clientType.clientVersion);
            client.put("osName", clientType.osName);
            client.put("osVersion", clientType.osVersion);
            if (clientType.androidSdkVersion != null) {
                client.put("androidSdkVersion", clientType.androidSdkVersion);
            }
            if (clientType.clientPlatform != null) {
                client.put("platform", clientType.clientPlatform);
            }
            client.put("hl", streamLocale.getLanguage());
            client.put("gl", streamLocale.getCountry());
            context.put("client", client);

            innerTubeBody.put("context", context);

            if (clientType.usePlayerEndpoint) {
                innerTubeBody.put("contentCheckOk", true);
                innerTubeBody.put("racyCheckOk", true);
                innerTubeBody.put("videoId", videoId);
            } else {
                JSONObject playerRequest = new JSONObject();
                playerRequest.put("contentCheckOk", true);
                playerRequest.put("racyCheckOk", true);
                playerRequest.put("videoId", videoId);
                innerTubeBody.put("playerRequest", playerRequest);
                innerTubeBody.put("disablePlayerResponse", false);
            }

            if (clientType.requireJS) {
                JSONObject configInfo = new JSONObject();
                configInfo.put("appInstallData", "");
                client.put("configInfo", configInfo);

                JSONObject user = new JSONObject();
                user.put("lockedSafetyMode", false);
                context.put("user", user);

                JSONObject contentPlaybackContext = new JSONObject();
                contentPlaybackContext.put(
                        "referer",
                        String.format("https://www.youtube.com/tv#/watch?v=%s", videoId)
                );
                contentPlaybackContext.put("html5Preference", "HTML5_PREF_WANTS");
                Integer signatureTimestamp = JavaScriptManager.getSignatureTimestamp();
                if (signatureTimestamp != null) {
                    contentPlaybackContext.put("signatureTimestamp", signatureTimestamp);
                }

                JSONObject devicePlaybackCapabilities = new JSONObject();
                devicePlaybackCapabilities.put("supportsVp9Encoding", true);
                devicePlaybackCapabilities.put("supportXhr", true);

                JSONObject playbackContext = new JSONObject();
                playbackContext.put("contentPlaybackContext", contentPlaybackContext);
                playbackContext.put("devicePlaybackCapabilities", devicePlaybackCapabilities);

                innerTubeBody.put("playbackContext", playbackContext);
            }
        } catch (JSONException e) {
            Logger.printException(() -> "Failed to create innerTubeBody", e);
        }

        return innerTubeBody.toString();
    }

    @SuppressWarnings("SameParameterValue")
    static HttpURLConnection getPlayerResponseConnectionFromRoute(Route.CompiledRoute route, ClientType clientType) throws IOException {
        var connection = Requester.getConnectionFromCompiledRoute(YT_API_URL, route);

        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("User-Agent", clientType.userAgent);
        // Not a typo. "Client-Name" uses the client type id.
        connection.setRequestProperty("X-YouTube-Client-Name", String.valueOf(clientType.id));
        connection.setRequestProperty("X-YouTube-Client-Version", clientType.clientVersion);

        connection.setUseCaches(false);
        connection.setDoOutput(true);

        connection.setConnectTimeout(CONNECTION_TIMEOUT_MILLISECONDS);
        connection.setReadTimeout(CONNECTION_TIMEOUT_MILLISECONDS);
        return connection;
    }
}