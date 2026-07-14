/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.shared.spoof.requests;

import static app.morphe.extension.shared.StringRef.str;
import static app.morphe.extension.shared.Utils.isNotEmpty;
import static app.morphe.extension.shared.Utils.submitOnBackgroundThread;
import static app.morphe.extension.shared.innertube.utils.AuthUtils.pageId;
import static app.morphe.extension.shared.spoof.SpoofVideoStreamsPatch.setCurrentVideoRequestHeader;
import static app.morphe.extension.shared.spoof.js.JavaScriptEngineSupport.supportsJavaScriptEngine;
import static app.morphe.extension.shared.spoof.js.JavaScriptManager.getDeobfuscatedStreamingData;
import static app.morphe.extension.shared.spoof.js.JavaScriptManager.getJavaScriptHash;
import static app.morphe.extension.shared.spoof.js.JavaScriptManager.getJavaScriptVariant;
import static app.morphe.extension.shared.spoof.requests.PlayerRoutes.GET_PLAYER_STREAMING_DATA;
import static app.morphe.extension.shared.spoof.requests.PlayerRoutes.GET_REEL_STREAMING_DATA;
import static app.morphe.extension.shared.spoof.requests.PlayerRoutes.SEND_SAVE_VIDEO_TO_WATCH_LATER;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.PlayerResponse;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.StreamingData;
import app.morphe.extension.shared.innertube.ReelItemWatchResponseOuterClass.ReelItemWatchResponse;
import app.morphe.extension.shared.oauth2.requests.OAuth2Requester;
import app.morphe.extension.shared.requests.Route;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.SharedYouTubeSettings;
import app.morphe.extension.shared.spoof.ClientType;

/**
 * Video streaming data. Fetching is tied to the behavior YT uses,
 * where this class fetches the streams only when YT fetches.
 * <p>
 * Effectively the cache expiration of these fetches is the same as the stock app,
 * since the stock app would not use expired streams and therefor
 * the extension replace stream hook is called only if YT
 * did use its own client streams.
 */
public class StreamOrDetailsDataRequest {

    private static volatile ClientType[] clientStreamOrderToUse =
            Arrays
            .stream(ClientType.values())
            .filter(
                    client
                            ->
                    client.endpoint == GET_PLAYER_STREAMING_DATA ||
                    client.endpoint == GET_REEL_STREAMING_DATA)
            .toArray(ClientType[]::new);

    public static void setClientOrderToUse(List<ClientType> availableClients, ClientType preferredClient) {
        Objects.requireNonNull(preferredClient);

        List<ClientType> orderToUse = new ArrayList<>(availableClients.size());
        orderToUse.add(preferredClient);

        for (ClientType client : availableClients) {
            if (client.requireJS && !supportsJavaScriptEngine()) {
                Logger.printDebug(() -> "Could not find JavaScript engine. Skipping JavaScript client: " + client.name());
                continue;
            }

            if (client != preferredClient) {
                orderToUse.add(client);
            }
        }

        clientStreamOrderToUse = orderToUse.toArray(new ClientType[0]);
        Logger.printDebug(() -> "Available spoof clients: " + orderToUse);
    }

    private static final String AUTHORIZATION_HEADER = "Authorization"; // Available only to logged-in users.
    private static final String PAGE_ID_HEADER = "X-Goog-PageId"; // Available only to logged-in users.

    private static final String[] REQUEST_HEADER_KEYS = {
            AUTHORIZATION_HEADER,
            "X-GOOG-API-FORMAT-VERSION",
            "X-Goog-Visitor-Id"
    };

    /**
     * TCP connection and HTTP read timeout.
     */
    private static final int HTTP_TIMEOUT_MILLISECONDS = 10 * 1000;

    /**
     * Any arbitrarily large value, but must be at least twice {@link #HTTP_TIMEOUT_MILLISECONDS}
     */
    private static int MAX_MILLISECONDS_TO_WAIT_FOR_FETCH;

    /**
     * Cache limit must be greater than the maximum number of videos open at once,
     * which theoretically is more than 4 (3 Shorts + one regular minimized video).
     * But instead use a much larger value, to handle if a video viewed a while ago
     * is somehow still referenced. Each stream is a small array of Strings
     * so memory usage is not a concern.
     */
    private static final Map<String, StreamOrDetailsDataRequest> streamCache = Collections.synchronizedMap(
            Utils.createSizeRestrictedMap(50));

    private static final Map<String, StreamOrDetailsDataRequest> detailsCache = Collections.synchronizedMap(
            Utils.createSizeRestrictedMap(50));

    private static volatile ClientType lastSpoofedClientType;

    /**
     * Used only for stats for nerds to show VR sign-in was used.
     */
    private static volatile boolean authHeadersOverrides;

    public static String getLastSpoofedClientName() {
        ClientType client = lastSpoofedClientType;
        if (client == null) {
            return "Unknown";
        } else {
            String clientName = client.friendlyName;
            if (client.supportsOAuth2 && authHeadersOverrides) {
                clientName += " Signed in";
            }
            return clientName;
        }
    }

    private final String videoId;

    private final Future<Object> future;

    private StreamOrDetailsDataRequest(@Nullable Route.CompiledRoute videoDetailsEndpoint,
                                       String videoId, Map<String, String> playerHeaders) {
        this.videoId = videoId;
        // Check if the videoDetailsEndpoint field is null (Stream fetching is enabled)
        boolean endpointIsNull = (videoDetailsEndpoint == null);
        // Set a different timeout for Stream fetching and Video Details fetching
        if (endpointIsNull) {
            MAX_MILLISECONDS_TO_WAIT_FOR_FETCH = 20 * 1000;
        } else {
            MAX_MILLISECONDS_TO_WAIT_FOR_FETCH = 5 * 1000;
        }
        this.future = submitOnBackgroundThread(() -> fetch(videoDetailsEndpoint, videoId, playerHeaders));
    }

    public static void fetchStreamRequest(String videoId, Map<String, String> fetchHeaders) {
        // Always fetch, even if there is an existing request for the same video.
        streamCache.put(videoId, new StreamOrDetailsDataRequest(null, videoId, fetchHeaders));
    }

    @Nullable
    public static StreamOrDetailsDataRequest getStreamRequestForVideoId(String videoId) {
        return streamCache.get(videoId);
    }

    public static StreamOrDetailsDataRequest getDetailsRequest(Route.CompiledRoute videoDetailsEndpoint,
                                                                 String videoId, Map<String, String> fetchHeaders) {
        // Always fetch, even if there is an existing request for the same video.
        StreamOrDetailsDataRequest request = new StreamOrDetailsDataRequest(videoDetailsEndpoint, videoId, fetchHeaders);
        detailsCache.put(videoId, request);
        return request;
    }

    private static void handleConnectionError(String toastMessage, @Nullable Exception ex, boolean showToast) {
        if (showToast) Utils.showToastShort(toastMessage);
        Logger.printInfo(() -> toastMessage, ex);
    }

    private static void handleDebugToast(String toastMessage,
                                         ClientType clientType) {
        if (BaseSettings.DEBUG.get() && BaseSettings.DEBUG_TOAST_ON_ERROR.get()) {
            Utils.showToastShort(
                String.format(
                    toastMessage,
                    clientType
                )
            );
        }
    }

    @Nullable
    private static HttpURLConnection send(@Nullable ClientType clientType,
                                          @Nullable String videoId,
                                          @Nullable Map<String, String> playerHeaders,
                                          boolean showErrorToasts) {
        Utils.verifyOffMainThread();

        Objects.requireNonNull(clientType);

        final boolean isStream = clientType.endpoint == GET_PLAYER_STREAMING_DATA || clientType.endpoint == GET_REEL_STREAMING_DATA;

        Objects.requireNonNull(videoId);
        if (isStream || clientType.endpoint == SEND_SAVE_VIDEO_TO_WATCH_LATER) {
            Objects.requireNonNull(playerHeaders);
        }

        final long startTime = System.currentTimeMillis();

        try {
            HttpURLConnection connection;
            connection = PlayerRoutes.getPlayerResponseConnectionFromRoute(clientType);
            connection.setConnectTimeout(HTTP_TIMEOUT_MILLISECONDS);
            connection.setReadTimeout(HTTP_TIMEOUT_MILLISECONDS);

            boolean authHeadersIncludes = false;
            authHeadersOverrides = false;

            if (playerHeaders != null) {
                if (isStream) {
                    setCurrentVideoRequestHeader(playerHeaders);
                }

                for (String key : REQUEST_HEADER_KEYS) {
                    String value = playerHeaders.get(key);

                    if (value != null) {
                        if (key.equals(AUTHORIZATION_HEADER)) {
                            if (clientType.supportsOAuth2) {
                                String authorization = OAuth2Requester.getAndUpdateAccessTokenIfNeeded();
                                if (authorization.isEmpty()) {
                                    // Access token is empty, the user has not signed in to VR.
                                    // YouTube/YouTube Music access tokens cannot be used with YouTube VR.
                                    // Do not set the header.
                                    Logger.printDebug(() -> "Not including request header: " + key);
                                    continue;
                                } else {
                                    // Access token is not empty, the user has signed in to VR.
                                    // Set the header.
                                    value = authorization;
                                    authHeadersOverrides = true;
                                }
                            } else if (!clientType.canLogin) {
                                Logger.printDebug(() -> "Not including request header: " + key);
                                continue;
                            }
                            authHeadersIncludes = true;
                        }

                        Logger.printDebug(() -> "Including request header: " + key);
                        connection.setRequestProperty(key, value);
                    }
                }
            }

            if (authHeadersIncludes) {
                if (!pageId.isEmpty()) {
                    Logger.printDebug(() -> "Including PAGE_ID_HEADER header: " + pageId);
                    connection.setRequestProperty(PAGE_ID_HEADER, pageId);
                }
            } else {
                if (clientType.requireLogin) {
                    Logger.printDebug(() -> "Skipping client since user is not logged in: " + clientType
                            + " videoId: " + videoId);
                    return null;
                }
            }

            Logger.printDebug(() -> "Fetching video " + (isStream ? "stream" : "details") +
                    " for: " + videoId + " using client: " + (clientType)
            );

            String innerTubeBody = PlayerRoutes.createInnertubeBody(clientType, videoId);
            byte[] requestBody = innerTubeBody.getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(requestBody.length);
            connection.getOutputStream().write(requestBody);

            final int responseCode = connection.getResponseCode();

            if (responseCode == 200) return connection;

            if (isStream) {
                // This situation likely means the patches are outdated.
                // Use a toast message that suggests updating.
                handleConnectionError("Playback error (App is outdated?) " + clientType + ": "
                                + responseCode + " response: " + connection.getResponseMessage(),
                        null, showErrorToasts);
            }
        } catch (SocketTimeoutException ex) {
            handleConnectionError("Connection timeout", ex, showErrorToasts);
        } catch (IOException ex) {
            handleConnectionError("Network error", ex, showErrorToasts);
        } catch (Exception ex) {
            Logger.printException(() -> "send failed", ex);
        } finally {
            Logger.printDebug(() -> "video: " + videoId + " took: " + (System.currentTimeMillis() - startTime) + "ms");
        }

        return null;
    }

    @Nullable
    private static Object buildPlayerStreamOrDetailsResponse(@Nullable ClientType clientType,
                                                             HttpURLConnection connection) {
        Objects.requireNonNull(clientType);
        final boolean returnStreamObject = clientType.endpoint == GET_PLAYER_STREAMING_DATA
                || clientType.endpoint == GET_REEL_STREAMING_DATA;

        // gzip encoding doesn't response with content length (-1),
        // but empty response body does.
        if (connection.getContentLength() == 0) {
            handleDebugToast(
                    String.format(
                            "Debug: Ignoring empty %s client (%s)",
                            returnStreamObject ? "spoof stream" : "get details",
                            clientType
                    ),
                    clientType
            );
            return null;
        }

        try (InputStream inputStream = connection.getInputStream()) {
            if (returnStreamObject) {
                PlayerResponse playerResponse = clientType.endpoint == GET_PLAYER_STREAMING_DATA
                        ? PlayerResponse.parseFrom(inputStream)
                        : ReelItemWatchResponse.parseFrom(inputStream).getPlayerResponse();
                var playabilityStatus = playerResponse.getPlayabilityStatus();
                String status = playabilityStatus.getStatus().name();

                if (!"OK".equals(status)) {
                    handleDebugToast("Debug: Ignoring unplayable video (%s)", clientType);
                    String reason = playabilityStatus.getReason();
                    if (isNotEmpty(reason)) {
                        Logger.printDebug(() -> String.format("Debug: Ignoring unplayable video (%s), reason: %s", clientType, reason));
                    }

                    return null;
                }

                PlayerResponse.Builder responseBuilder = playerResponse.toBuilder();
                if (!playerResponse.hasStreamingData()) {
                    handleDebugToast("Debug: Ignoring empty streaming data (%s)", clientType);
                    return null;
                }

                // Android Studio only supports the HLS protocol for live streams.
                // HLS protocol can theoretically be played with ExoPlayer,
                // but the related code has not yet been implemented.
                // If DASH protocol is not available, the client will be skipped.
                StreamingData streamingData = playerResponse.getStreamingData();
                if (streamingData.getAdaptiveFormatsCount() == 0) {
                    handleDebugToast("Debug: Ignoring empty adaptiveFormat (%s)", clientType);
                    return null;
                }

                if (clientType.requireJS) {
                    var deobfuscatedStreamingData = getDeobfuscatedStreamingData(streamingData);
                    if (deobfuscatedStreamingData == null) {
                        handleDebugToast("Debug: Ignoring obfuscated streamingData (%s)", clientType);
                        return null;
                    }
                    responseBuilder.setStreamingData(deobfuscatedStreamingData);
                }

                return responseBuilder.build().toByteArray();
            } else {
                String response = new BufferedReader(new InputStreamReader(inputStream))
                                    .lines()
                                    .collect(Collectors.joining("\n"));

                JSONObject jsonResponse = new JSONObject(response);

                if (clientType.endpoint.equals(PlayerRoutes.GET_CHANNEL_FROM_ID)) {
                    return jsonResponse
                            .getJSONObject("videoDetails")
                            .getString("channelId");
                } else if (clientType.endpoint.equals(PlayerRoutes.SEND_SAVE_VIDEO_TO_WATCH_LATER)) {
                    return response;
                }
            }
        } catch (IOException ex) {
            Logger.printException(() -> "Failed to write player response for video stream or details", ex);
            return null;
        } catch (JSONException ex) {
            Logger.printException(() -> "Failed to create jsonResponse object for video details", ex);
            return null;
        } catch (Exception ex) {
            Logger.printDebug(() -> "Failed to fetch stream for client: " + clientType);
        }
        return null;
    }

    private static Object fetch(@Nullable Route.CompiledRoute videoDetailsEndpoint,
                                String videoId, @Nullable Map<String, String> playerHeaders) {
        if (videoDetailsEndpoint == null) {
            final long fetchStartTime = System.currentTimeMillis();
            final boolean debugEnabled = BaseSettings.DEBUG.get();

            // Retry with different client if empty response body is received.
            int i = 0;
            for (ClientType clientTypeStream : clientStreamOrderToUse) {
                Logger.printDebug(() -> "Fetching using endpoint: " + clientTypeStream.endpoint.getCompiledRoute());

                // Show an error if the last client type fails, or if debug is enabled then show for all attempts.
                final boolean showErrorToast = (++i == clientStreamOrderToUse.length) || debugEnabled;

                HttpURLConnection connection = send(clientTypeStream, videoId, playerHeaders, showErrorToast);
                Logger.printDebug(() -> "Connection result: " + connection);
                if (connection != null) {
                    Object playerResponseBuffer = buildPlayerStreamOrDetailsResponse(clientTypeStream, connection);

                    if (playerResponseBuffer != null) {
                        lastSpoofedClientType = clientTypeStream;

                        if (clientTypeStream.requireJS) {
                            Logger.printDebug(() -> "End of fetch for JavaScript required client" +
                                    ", video: " + videoId +
                                    ", hash: " + getJavaScriptHash() +
                                    ", variant: " + getJavaScriptVariant() +
                                    ", took: " + (System.currentTimeMillis() - fetchStartTime) + "ms");
                        }

                        return playerResponseBuffer;
                    }
                }
            }

            lastSpoofedClientType = null;
            handleConnectionError(str("morphe_spoof_video_streams_no_clients_toast"), null, true);

            var preferredClient = clientStreamOrderToUse[0];
            if (preferredClient != ClientType.ANDROID_VR_1_64 && preferredClient != ClientType.ANDROID_VR_1_65
                    && !SharedYouTubeSettings.OAUTH2_REFRESH_TOKEN.get().isBlank()) {
                handleConnectionError(str("morphe_spoof_video_streams_no_clients_suggest_vr_toast"), null, true);
            }
        } else {
            for (ClientType clientTypeDetails : ClientType.values()) {
                if (clientTypeDetails.endpoint == videoDetailsEndpoint) {
                    Logger.printDebug(() -> "Fetching using endpoint: " + clientTypeDetails.endpoint.getCompiledRoute());

                    HttpURLConnection connection =
                        send(clientTypeDetails, videoId, playerHeaders, false);
                    Logger.printDebug(() -> "Connection result: " + connection);
                    if (connection != null) {
                        return buildPlayerStreamOrDetailsResponse(clientTypeDetails, connection);
                    }
                }
            }
        }
        return null;
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public boolean fetchIsDone() {
        return future.isDone();
    }

    @Nullable
    public Object getStreamDetails() {
        try {
            // This hook is always called off the main thread,
            // but this can later be called for the same video ID from the main thread.
            // This is not a concern, since the fetch will always be finished
            // and never block the main thread.
            // But if debugging, then still verify this is the situation.
            if (BaseSettings.DEBUG.get() && !fetchIsDone() && Utils.isCurrentlyOnMainThread()) {
                Logger.printException(() -> "Debug: Blocking main thread");
            }
            return future.get(MAX_MILLISECONDS_TO_WAIT_FOR_FETCH, TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            Logger.printInfo(() -> "getStreamDetails timed out", ex);
            future.cancel(true);
        } catch (CancellationException ex) {
            Logger.printInfo(() -> "getStreamDetails was previously cancelled");
        } catch (InterruptedException ex) {
            Logger.printException(() -> "getStreamDetails interrupted", ex);
            future.cancel(true);
            Thread.currentThread().interrupt(); // Restore interrupt status flag.
        } catch (ExecutionException ex) {
            Logger.printException(() -> "getStreamDetails failure", ex);
        }

        return null;
    }

    @NonNull
    @Override
    public String toString() {
        return "StreamOrDetailsDataRequest{" + "videoId='" + videoId + '\'' + '}';
    }
}
