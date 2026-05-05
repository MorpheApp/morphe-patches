/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to Morphe contributions.
 */

package app.morphe.extension.shared.spoof.requests;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.requests.Requester;
import app.morphe.extension.shared.requests.Route;
import app.morphe.extension.shared.settings.AppLanguage;
import app.morphe.extension.shared.spoof.ClientType;
import app.morphe.extension.shared.spoof.SpoofVideoStreamsOrGetVideoDetailsPatch;
import app.morphe.extension.shared.spoof.js.JavaScriptManager;

public final class PlayerRoutes {

    static final Route.CompiledRoute GET_CHANNEL_FROM_ID = new Route(
            Route.Method.POST,
            "player" +
                    "?prettyPrint=false" +
                    "&fields=videoDetails.channelId"
    ).compile();
    public static final String getChannelIDDetailsName = "getChannelID";

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

    static final Route.CompiledRoute SEND_SAVE_VIDEO_TO_PLAYLIST = new Route(
            Route.Method.POST,
            "browse/edit_playlist" +
                    "?fields=status,playlistEditResults"
    ).compile();
    public static final String saveToWatchLaterDetailsName = "saveToWatchLater";

    private static final String YT_API_URL = "https://youtubei.googleapis.com/youtubei/v1/";

    /**
     * TCP connection and HTTP read timeout
     */
    private static final int CONNECTION_TIMEOUT_MILLISECONDS = 10 * 1000; // 10 Seconds.

    private PlayerRoutes() {
    }

    static String createInnertubeBody(String detailsToFetch,
                                      ClientType.Stream clientTypeStream,
                                      ClientType.Details clientTypeDetails,
                                      String videoId) {
        class ClientWrapper {
            final String deviceMake, deviceModel, clientName, clientVersion, osName, osVersion, androidSdkVersion, clientPlatform;
            final boolean usePlayerEndpoint, requireJS;

            ClientWrapper(ClientType.Stream stream, ClientType.Details details) {
                boolean isStream = stream != null;

                this.deviceMake = isStream ? stream.deviceMake : details.deviceMake;
                this.deviceModel = isStream ? stream.deviceModel : details.deviceModel;
                this.clientName = isStream ? stream.clientName : details.clientName;
                this.clientVersion = isStream ? stream.clientVersion : details.clientVersion;
                this.osName = isStream ? stream.osName : details.osName;
                this.osVersion = isStream ? stream.osVersion : details.osVersion;
                this.androidSdkVersion = isStream ? stream.androidSdkVersion : details.androidSdkVersion;
                this.clientPlatform = isStream ? stream.clientPlatform : details.clientPlatform;
                this.usePlayerEndpoint = isStream ? stream.usePlayerEndpoint : details.usePlayerEndpoint;
                this.requireJS = isStream ? stream.requireJS : details.requireJS;
            }
        }
        ClientWrapper clientData = new ClientWrapper(clientTypeStream, clientTypeDetails);

        JSONObject innerTubeBody = new JSONObject();

        try {
            JSONObject context = new JSONObject();

            AppLanguage language = SpoofVideoStreamsOrGetVideoDetailsPatch.getLanguageOverride();
            if (language == null) {
                // Force original audio has not overridden the language.
                language = AppLanguage.DEFAULT;
            }
            Locale streamLocale = language.getLocale();

            JSONObject client = new JSONObject();
            client.put("deviceMake", clientData.deviceMake);
            client.put("deviceModel", clientData.deviceModel);
            client.put("clientName", clientData.clientName);
            client.put("clientVersion", clientData.clientVersion);
            client.put("osName", clientData.osName);
            client.put("osVersion", clientData.osVersion);
            String androidSdkVersion = clientData.androidSdkVersion;
            if (androidSdkVersion != null && !androidSdkVersion.isEmpty()) {
                client.put("androidSdkVersion", androidSdkVersion);
            }
            String platform = clientData.clientPlatform;
            if (platform != null && !platform.isEmpty()) {
                client.put("platform", platform);
            }
            JSONObject user = new JSONObject();
            user.put("lockedSafetyMode", false);
            if (detailsToFetch.isEmpty()) {
                client.put("hl", streamLocale.getLanguage());
                client.put("gl", streamLocale.getCountry());
            } else {
                context.put("user", user);
            }
            context.put("client", client);

            boolean usePlayerEndpoint = clientData.usePlayerEndpoint;
            if (usePlayerEndpoint) {
                innerTubeBody.put("contentCheckOk", true);
                innerTubeBody.put("racyCheckOk", true);
                innerTubeBody.put("videoId", videoId);
                if (Objects.equals(detailsToFetch, saveToWatchLaterDetailsName)) {
                    innerTubeBody.put("playlistId", "WL");
                    innerTubeBody.put("excludeWatchLater", false);

                    JSONObject action = new JSONObject();
                    action.put("action", "ACTION_ADD_VIDEO");
                    action.put("addedVideoId", videoId);

                    JSONArray actions = new JSONArray();
                    actions.put(action);

                    innerTubeBody.put("actions", actions);
                }
            } else {
                JSONObject playerRequest = new JSONObject();
                playerRequest.put("contentCheckOk", true);
                playerRequest.put("racyCheckOk", true);
                playerRequest.put("videoId", videoId);
                innerTubeBody.put("playerRequest", playerRequest);
                innerTubeBody.put("disablePlayerResponse", false);
            }

            if (clientData.requireJS) {
                JSONObject configInfo = new JSONObject();
                configInfo.put("appInstallData", "");
                client.put("configInfo", configInfo);

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

            innerTubeBody.put("context", context);
        } catch (JSONException e) {
            Logger.printException(() -> "Failed to create innerTubeBody", e);
        }

        return innerTubeBody.toString();
    }

    @SuppressWarnings("SameParameterValue")
    static HttpURLConnection getPlayerResponseConnectionFromRoute(Route.CompiledRoute route, List<String> clientInfo) throws IOException {
        var connection = Requester.getConnectionFromCompiledRoute(YT_API_URL, route);

        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("User-Agent", clientInfo.get(0));
        // Not a typo. "Client-Name" uses the client type id.
        connection.setRequestProperty("X-YouTube-Client-Name", clientInfo.get(1));
        connection.setRequestProperty("X-YouTube-Client-Version", clientInfo.get(2));

        connection.setUseCaches(false);
        connection.setDoOutput(true);

        connection.setConnectTimeout(CONNECTION_TIMEOUT_MILLISECONDS);
        connection.setReadTimeout(CONNECTION_TIMEOUT_MILLISECONDS);
        return connection;
    }
}