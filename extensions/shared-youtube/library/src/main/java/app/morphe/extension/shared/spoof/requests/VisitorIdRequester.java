/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.shared.spoof.requests;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.requests.Requester;
import app.morphe.extension.shared.spoof.ClientType;

public final class VisitorIdRequester {
    private static final String YT_API_URL_FORMAT = "https://youtubei.googleapis.com/youtubei/v1/%s" +
            "?prettyPrint=false&fields=responseContext.visitorData";

    // To prevent bot scores from increasing, a different visitorId must be used for each client.
    // Generally, the expiration date of a visitorId is quite long (over 2 years).
    //
    // TODO: Implement a feature to save the visitorId and fetchedTime for each client to sharedPreference.
    private static final Map<ClientType, String> cache = Collections.synchronizedMap(
            Utils.createSizeRestrictedMap(ClientType.values().length));

    public static String getVisitorId(ClientType clientType) {
        String cachedVisitorId = cache.get(clientType);
        if (Utils.isNotEmpty(cachedVisitorId)) {
            return cachedVisitorId;
        } else if (Utils.isNetworkConnected()) {
            String fetchedVisitorId = send(clientType);
            if (Utils.isNotEmpty(fetchedVisitorId)) {
                Logger.printDebug(() -> "client: " + clientType + ", visitorId: " + fetchedVisitorId);
                cache.put(clientType, fetchedVisitorId);
            }

            return fetchedVisitorId;
        }

        return null;
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
        } catch (JSONException e) {
            Logger.printException(() -> "Failed to create innerTubeBody", e);
        }

        return innerTubeBody.toString();
    }

    @Nullable
    private static String send(ClientType clientType) {
        JSONObject response;

        try {
            final long start = System.currentTimeMillis();
            response = Utils.submitOnBackgroundThread(() -> {
                final int connectionTimeoutMillis = 5000;
                String url = String.format(YT_API_URL_FORMAT,
                        // TVHTML5 does not support the '/visitor_id' endpoint.
                        clientType.id == 7 ? "guide" : "visitor_id"
                );
                HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
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
                    return Requester.parseJSONObjectAndDisconnect(connection);
                }
                connection.disconnect();
                return null;
            }).get();

            Logger.printDebug(() -> "Fetch took: " + (System.currentTimeMillis() - start) + "ms");
            if (response != null) {
                return response.getJSONObject("responseContext").getString("visitorData");
            }
        } catch (ExecutionException | InterruptedException ex) {
            Logger.printException(() -> "Failed to fetch visitor data", ex);
        } catch (JSONException ex) {
            Logger.printException(() -> "Failed to parse visitor data", ex);
        }

        return null;
    }
}