/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.shared.spoof.requests;

import androidx.annotation.GuardedBy;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.requests.Requester;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.SharedYouTubeSettings;
import app.morphe.extension.shared.spoof.ClientType;

public final class VisitorIdRequester {

    private record VisitorData(String visitorId, long fetchedTime) {
        private static final long VISITOR_ID_EXPIRATION_MS = 2L * 365 * 24 * 60 * 60 * 1000; // 2 years

        boolean isNotExpired() {
            return Utils.isNotEmpty(visitorId) && System.currentTimeMillis()
                    - fetchedTime < VISITOR_ID_EXPIRATION_MS;
        }

        String getFetchedTimeFormatted() {
            return Instant.ofEpochMilli(fetchedTime).atZone(ZoneOffset.UTC).toString();
        }
    }

    private static final String YT_API_URL_FORMAT = "https://youtubei.googleapis.com/youtubei/v1/%s" +
            "?prettyPrint=false&fields=responseContext.visitorData";


    // To prevent bot scores from increasing, a different visitorId must be used for each client.
    // Generally, the expiration date of a visitorId is quite long (over 2 years).
    @GuardedBy("itself")
    private static final Map<ClientType, VisitorData> cache = new HashMap<>(
            2 * ClientType.values().length);

    static {
        loadVisitorIds();
    }

    private static void loadVisitorIds() {
        String clientIds = SharedYouTubeSettings.SPOOF_VIDEO_STREAMS_CLIENT_IDS.get();
        if (clientIds.isEmpty()) {
            return;
        }
        try {
            JSONObject json = new JSONObject(clientIds);
            synchronized (cache) {
                for (ClientType clientType : ClientType.values()) {
                    JSONObject visitorJson = json.optJSONObject(clientType.name());
                    if (visitorJson != null) {
                        VisitorData visitor = new VisitorData(
                                visitorJson.getString("visitorId"),
                                visitorJson.getLong("fetchedTime")
                        );
                        final boolean isNotExpired = visitor.isNotExpired();
                        if (isNotExpired) {
                            cache.put(clientType, visitor);
                        }
                        // Don't log visitor id and use UTC timezone to not leak device timezone.
                        Logger.printDebug(() -> (isNotExpired ? "Loaded visitorId" : "Ignoring expired")
                                + " clientType: " + clientType
                                + " fetchedTime: " + visitor.getFetchedTimeFormatted());
                    }
                }
            }
        } catch (JSONException ex) {
            Logger.printException(() -> "Failed to load visitor IDs from saved data", ex);
            SharedYouTubeSettings.SPOOF_VIDEO_STREAMS_CLIENT_IDS.resetToDefault();
        }
    }

    private static void saveVisitorId(ClientType clientType, String visitorId, boolean updatedByPlayer) {
        Logger.printDebug(() -> "Updating visitorId for clientType: " + clientType + " updated by player: " + updatedByPlayer);

        synchronized (cache) {
            cache.put(clientType, new VisitorData(visitorId, System.currentTimeMillis()));
            JSONObject json = new JSONObject();
            try {
                for (Map.Entry<ClientType, VisitorData> entry : cache.entrySet()) {
                    VisitorData visitor = entry.getValue();
                    JSONObject data = new JSONObject()
                            .put("visitorId", visitor.visitorId)
                            .put("fetchedTime", visitor.fetchedTime);
                    json.put(entry.getKey().name(), data);
                }
                SharedYouTubeSettings.SPOOF_VIDEO_STREAMS_CLIENT_IDS.save(json.toString());
            } catch (JSONException ex) {
                Logger.printException(() -> "Failed to save visitor IDs", ex);
            }
        }
    }

    @Nullable
    public static String getVisitorId(ClientType clientType) {
        VisitorData cachedData;
        synchronized (cache) {
            cachedData = cache.get(clientType);
        }
        if (cachedData != null && cachedData.isNotExpired()) {
            return cachedData.visitorId;
        }

        if (!Utils.isNetworkConnected()) {
            return null;
        }

        String fetchedVisitorId = send(clientType);
        if (Utils.isNotEmpty(fetchedVisitorId)) {
            saveVisitorId(clientType, fetchedVisitorId, false);
        }
        return fetchedVisitorId;
    }

    /**
     * @param visitorId The visitor id included in the response from the '/player' or '/reel/reel_item_watch' endpoint.
     */
    public static void updateVisitorIdIfNeed(ClientType clientType, @Nullable String visitorId) {
        // If it is not null, it means a new visitor id has been issued.
        if (Utils.isNotEmpty(visitorId)) {
            saveVisitorId(clientType, visitorId, true);
        }
    }

    private static String createInnertubeBody(ClientType clientType) {
        JSONObject innerTubeBody = new JSONObject();

        try {
            JSONObject context = new JSONObject();

            JSONObject client = new JSONObject();
            client.put("clientName", clientType.clientName);
            client.put("clientVersion", clientType.clientVersion);
            String platform = clientType.clientPlatform;
            if (Utils.isNotEmpty(platform)) {
                client.put("platform", platform);
            }
            client.put("hl", "en-GB");
            client.put("gl", "GB");
            client.put("utcOffsetMinutes", 0);
            context.put("client", client);

            JSONArray internalExperimentFlags = new JSONArray();

            JSONObject request = new JSONObject();
            request.put("internalExperimentFlags", internalExperimentFlags);
            request.put("useSsl", true);
            context.put("request", request);

            JSONObject user = new JSONObject();
            user.put("lockedSafetyMode", false);
            context.put("user", user);

            innerTubeBody.put("context", context);
        } catch (JSONException ex) {
            Logger.printException(() -> "Failed to create innerTubeBody", ex);
        }

        return innerTubeBody.toString();
    }

    @Nullable
    private static String send(ClientType clientType) {
        final long start = System.currentTimeMillis();
        try {
            Utils.verifyOffMainThread();

            final int connectionTimeoutMillis = 5000;
            String url = String.format(YT_API_URL_FORMAT,
                    // TVHTML5 does not support the '/visitor_id' endpoint.
                    clientType.id == 7 ? "guide" : "visitor_id"
            );
            HttpURLConnection connection = Requester.openConnection(url);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Accept-Language", "en-GB, en;q=0.9");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("User-Agent", clientType.userAgent);
            connection.setRequestProperty("X-YouTube-Client-Name", String.valueOf(clientType.id));
            connection.setRequestProperty("X-YouTube-Client-Version", clientType.clientVersion);
            connection.setConnectTimeout(connectionTimeoutMillis);
            connection.setReadTimeout(connectionTimeoutMillis);

            String innerTubeBody = createInnertubeBody(clientType);
            byte[] requestBody = innerTubeBody.getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(requestBody.length);
            connection.getOutputStream().write(requestBody);

            final int responseCode = connection.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                // Parse but do not disconnect because connection may be reused in the near future.
                JSONObject response = Requester.parseJSONObject(connection);
                return response.getJSONObject("responseContext").getString("visitorData");
            }

            if (BaseSettings.DEBUG.get()) {
                String responseMessage = connection.getResponseMessage();
                Logger.LogMessage logMessage = () -> "Debug: Unexpected visitorId response code: "
                        + responseCode + " message: " + responseMessage;
                if (BaseSettings.DEBUG_TOAST_ON_ERROR.get()) {
                    Logger.printException(logMessage);
                } else {
                    Logger.printDebug(logMessage);
                }
            }
        } catch (IOException ex) {
            Logger.printException(() -> "Failed to fetch visitor data", ex);
        } catch (JSONException ex) {
            Logger.printException(() -> "Failed to parse visitor data", ex);
        } catch (Exception ex) {
            Logger.printException(() -> "send failure", ex);
        } finally {
            Logger.printDebug(() -> "Fetch took: " + (System.currentTimeMillis() - start) + "ms");
        }

        return null;
    }
}
