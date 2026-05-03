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

import static app.morphe.extension.shared.StringRef.str;
import static app.morphe.extension.shared.Utils.isNotEmpty;
import static app.morphe.extension.shared.spoof.js.JavaScriptEngineSupport.supportsJavaScriptEngine;
import static app.morphe.extension.shared.spoof.js.JavaScriptManager.getDeobfuscatedStreamingData;
import static app.morphe.extension.shared.spoof.js.JavaScriptManager.getJavaScriptHash;
import static app.morphe.extension.shared.spoof.js.JavaScriptManager.getJavaScriptVariant;
import static app.morphe.extension.shared.spoof.requests.PlayerRoutes.GET_CHANNEL_FROM_ID;
import static app.morphe.extension.shared.spoof.requests.PlayerRoutes.GET_PLAYER_STREAMING_DATA;
import static app.morphe.extension.shared.spoof.requests.PlayerRoutes.GET_REEL_STREAMING_DATA;
import static app.morphe.extension.shared.spoof.requests.PlayerRoutes.SEND_SAVE_VIDEO_TO_PLAYLIST;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.util.Pair;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
public class StreamingDataRequest {

    public static final String getChannelIDDetailsName = "getChannelID";
    public static final String saveToWatchLaterDetailsName = "saveToWatchLater";

    private static volatile ClientType.Stream[] clientStreamOrderToUse = ClientType.Stream.values();

    public static void setClientOrderToUse(List<ClientType.Stream> availableClients, ClientType.Stream preferredClient) {
        Objects.requireNonNull(preferredClient);

        int availableClientSize = availableClients.size();
        if (!availableClients.contains(preferredClient)) {
            availableClientSize++;
        }

        clientStreamOrderToUse = new ClientType.Stream[availableClientSize];
        clientStreamOrderToUse[0] = preferredClient;

        int i = 1;
        for (ClientType.Stream c : availableClients) {
            if (c.requireJS && !supportsJavaScriptEngine()) {
                Logger.printDebug(() -> "Could not find JavaScript engine. Skipping JavaScript client: " + c.name());
                continue;
            }

            if (c != preferredClient) {
                clientStreamOrderToUse[i++] = c;
            }
        }

        Logger.printDebug(() -> "Available spoof clients: " + Arrays.toString(clientStreamOrderToUse));
    }

    private static final String AUTHORIZATION_HEADER = "Authorization";

    private static final String[] REQUEST_HEADER_KEYS = {
            AUTHORIZATION_HEADER, // Available only to logged-in users.
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
    private static final int MAX_MILLISECONDS_TO_WAIT_FOR_FETCH = 20 * 1000;

    /**
     * Cache limit must be greater than the maximum number of videos open at once,
     * which theoretically is more than 4 (3 Shorts + one regular minimized video).
     * But instead use a much larger value, to handle if a video viewed a while ago
     * is somehow still referenced. Each stream is a small array of Strings
     * so memory usage is not a concern.
     */
    private static final Map<String, StreamingDataRequest> streamCache = Collections.synchronizedMap(
            Utils.createSizeRestrictedMap(50));

    private static final Map<String, StreamingDataRequest> detailsCache = Collections.synchronizedMap(
            Utils.createSizeRestrictedMap(50));

    private static volatile ClientType.Stream lastSpoofedClientType;

    /**
     * Used only for stats for nerds to show VR sign-in was used.
     */
    private static volatile boolean authHeadersOverrides;

    public static String getLastSpoofedClientName() {
        ClientType.Stream client = lastSpoofedClientType;
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

    /**
     * Debug purpose pool
     */
    private static final ThreadPoolExecutor backgroundThreadPool = new ThreadPoolExecutor(
            5,
            Integer.MAX_VALUE,
            10,
            TimeUnit.SECONDS,
            new SynchronousQueue<>(),
            r -> {
                Thread t = new Thread(r);
                t.setPriority(Thread.MAX_PRIORITY);
                return t;
            });
    public static <T> Future<T> submitOnBackgroundThread(Callable<T> call) {
        return backgroundThreadPool.submit(call);
    }

    private StreamingDataRequest(String detailsToFetch, String videoId, Map<String, String> playerHeaders) {
        Objects.requireNonNull(playerHeaders);
        this.videoId = videoId;

        this.future = submitOnBackgroundThread(() -> fetch(detailsToFetch, videoId, playerHeaders));
    }

    public static void fetchStreamRequest(String videoId, Map<String, String> fetchHeaders) {
        // Always fetch, even if there is an existing request for the same video.
        streamCache.put(videoId, new StreamingDataRequest("", videoId, fetchHeaders));
    }

    @Nullable
    public static StreamingDataRequest getStreamRequestForVideoId(String videoId) {
        return streamCache.get(videoId);
    }

    public static void fetchDetailsRequest(String detailsToFetch, String videoId, Map<String, String> fetchHeaders) {
        // Always fetch, even if there is an existing request for the same video.
        detailsCache.put(videoId, new StreamingDataRequest(detailsToFetch, videoId, fetchHeaders));
    }

    @Nullable
    public static StreamingDataRequest getDetailsRequestForVideoId(String videoId) {
        return detailsCache.get(videoId);
    }

    private static void handleConnectionError(String toastMessage, @Nullable Exception ex, boolean showToast) {
        if (showToast) Utils.showToastShort(toastMessage);
        Logger.printInfo(() -> toastMessage, ex);
    }

    private static void handleDebugToast(String toastMessage, ClientType.Stream clientType) {
        if (BaseSettings.DEBUG.get() && BaseSettings.DEBUG_TOAST_ON_ERROR.get()) {
            Utils.showToastShort(String.format(toastMessage, clientType));
        }
    }

    @Nullable
    private static HttpURLConnection send(ClientType.Stream clientTypeStream,
                                          String detailsToFetch,
                                          ClientType.Details clientTypeDetails,
                                          String videoId,
                                          Map<String, String> playerHeaders,
                                          boolean showErrorToasts) {
        Objects.requireNonNull(videoId);
        Objects.requireNonNull(playerHeaders);

        final boolean isStream = detailsToFetch.isEmpty();

        final long startTime = System.currentTimeMillis();

        try {
            Route.CompiledRoute route;
            HttpURLConnection connection;
            if (isStream) {
                Objects.requireNonNull(clientTypeStream);

                route = clientTypeStream.usePlayerEndpoint ? GET_PLAYER_STREAMING_DATA : GET_REEL_STREAMING_DATA;
                connection =
                    PlayerRoutes.getPlayerResponseConnectionFromRoute(
                        route,

                        List.of(
                            clientTypeStream.userAgent,
                            clientTypeStream.clientName,
                            clientTypeStream.clientVersion
                        )
                    );
            } else {
                route = switch (detailsToFetch) {
                    case saveToWatchLaterDetailsName -> SEND_SAVE_VIDEO_TO_PLAYLIST;
                    case getChannelIDDetailsName -> GET_CHANNEL_FROM_ID;
                    default -> throw new IllegalStateException("Unexpected detailsToFetch value: " + detailsToFetch);
                };
                connection =
                    PlayerRoutes.getPlayerResponseConnectionFromRoute(
                        route,

                        List.of(
                            clientTypeDetails.userAgent,
                            clientTypeDetails.clientName,
                            clientTypeDetails.clientVersion
                        )
                    );
            }
            connection.setConnectTimeout(HTTP_TIMEOUT_MILLISECONDS);
            connection.setReadTimeout(HTTP_TIMEOUT_MILLISECONDS);

            boolean authHeadersIncludes = false;
            authHeadersOverrides = false;

            for (String key : REQUEST_HEADER_KEYS) {
                String value = playerHeaders.get(key);

                if (value != null) {
                    if (key.equals(AUTHORIZATION_HEADER)) {
                        if (isStream) {
                            if (clientTypeStream.supportsOAuth2) {
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
                            } else if (!clientTypeStream.canLogin) {
                                Logger.printDebug(() -> "Not including request header: " + key);
                                continue;
                            }
                        }
                        authHeadersIncludes = true;
                    }

                    Logger.printDebug(() -> "Including request header: " + key);
                    connection.setRequestProperty(key, value);
                }
            }

            if (isStream && !authHeadersIncludes && clientTypeStream.requireLogin) {
                Logger.printDebug(() -> "Skipping client since user is not logged in: " + clientTypeStream
                        + " videoId: " + videoId);
                return null;
            }

            Logger.printDebug(
                ()
                    ->
                String.format(
                    "Fetching video %s for: " + videoId + " using client: %s",

                    isStream ? "stream" : "details",
                    isStream ? clientTypeStream : clientTypeDetails
                )
            );

            String innerTubeBody =
                PlayerRoutes.createInnertubeBody(
                    detailsToFetch,

                    (isStream
                    ?
                        List.of(
                            new Pair<>(clientTypeStream.deviceMake, false),
                            new Pair<>(clientTypeStream.deviceModel, false),
                            new Pair<>(clientTypeStream.clientName, false),
                            new Pair<>(clientTypeStream.clientVersion, false),
                            new Pair<>(clientTypeStream.osName, false),
                            new Pair<>(clientTypeStream.osVersion, false),
                            new Pair<>(clientTypeStream.androidSdkVersion, false),
                            new Pair<>(clientTypeStream.clientPlatform, false),
                            new Pair<>("", clientTypeStream.usePlayerEndpoint),
                            new Pair<>("", clientTypeStream.requireJS)
                        )
                    :
                        List.of(
                            new Pair<>(clientTypeDetails.deviceMake, false),
                            new Pair<>(clientTypeDetails.deviceModel, false),
                            new Pair<>(clientTypeDetails.clientName, false),
                            new Pair<>(clientTypeDetails.clientVersion, false),
                            new Pair<>(clientTypeDetails.osName, false),
                            new Pair<>(clientTypeDetails.osVersion, false),
                            new Pair<>(clientTypeDetails.androidSdkVersion, false),
                            new Pair<>("", false),
                            new Pair<>("", true),
                            new Pair<>("", false)
                        )),

                    videoId
                );
            byte[] requestBody = innerTubeBody.getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(requestBody.length);
            connection.getOutputStream().write(requestBody);

            final int responseCode = connection.getResponseCode();

            if (responseCode == 200) return connection;

            if (isStream) {
                // This situation likely means the patches are outdated.
                // Use a toast message that suggests updating.
                handleConnectionError("Playback error (App is outdated?) " + clientTypeStream + ": "
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
    private static byte[] buildPlayerResponseBuffer(ClientType.Stream clientType,
                                                    HttpURLConnection connection) {
        // gzip encoding doesn't response with content length (-1),
        // but empty response body does.
        if (connection.getContentLength() == 0) {
            handleDebugToast("Debug: Ignoring empty spoof stream client (%s)", clientType);
            return null;
        }

        try (InputStream inputStream = connection.getInputStream()) {
            PlayerResponse playerResponse = clientType.usePlayerEndpoint
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
        } catch (IOException ex) {
            Logger.printException(() -> "Failed to write player response to buffer array", ex);
            return null;
        }
    }

    private static Object fetch(String detailsToFetch, String videoId, Map<String, String> playerHeaders) {
        Logger.printDebug(() -> detailsToFetch);

        if (detailsToFetch.isEmpty()) {
            final boolean debugEnabled = BaseSettings.DEBUG.get();
            final long fetchStartTime = System.currentTimeMillis();

            // Retry with different client if empty response body is received.
            int i = 0;
            for (ClientType.Stream clientType : clientStreamOrderToUse) {
                // Show an error if the last client type fails, or if debug is enabled then show for all attempts.
                final boolean showErrorToast = (++i == clientStreamOrderToUse.length) || debugEnabled;

                HttpURLConnection connection =
                    send(clientType, detailsToFetch, null, videoId, playerHeaders, showErrorToast);
                if (connection != null) {
                    byte[] playerResponseBuffer = buildPlayerResponseBuffer(clientType, connection);

                    if (playerResponseBuffer != null) {
                        lastSpoofedClientType = clientType;

                        if (clientType.requireJS) {
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
            if (preferredClient != ClientType.Stream.ANDROID_VR_1_64 && preferredClient != ClientType.Stream.ANDROID_VR_1_65
                    && !SharedYouTubeSettings.OAUTH2_REFRESH_TOKEN.get().isBlank()) {
                handleConnectionError(str("morphe_spoof_video_streams_no_clients_suggest_vr_toast"), null, true);
            }
        } else {
            for (ClientType.Details clientType : ClientType.Details.values()) {
                if (Objects.equals(clientType.detailsToFetch, detailsToFetch)) {
                    HttpURLConnection connection =
                        send(null, detailsToFetch, clientType, videoId, playerHeaders, false);

                    if (connection != null) {
                        try (BufferedReader reader =
                             new BufferedReader(
                                 new InputStreamReader(connection.getInputStream())
                             )
                        ) {
                            StringBuilder jsonBuilder = new StringBuilder();
                            String line;

                            while ((line = reader.readLine()) != null) {
                                jsonBuilder.append(
                                    String.format(
                                        "%s\n",

                                        line
                                    )
                                );
                            }

                            String jsonBuilderString = jsonBuilder.toString();
                            JSONObject jsonResponse = new JSONObject(jsonBuilderString);

                            switch (detailsToFetch) {
                                case StreamingDataRequest.getChannelIDDetailsName -> {
                                    return jsonResponse
                                            .getJSONObject("videoDetails")
                                            .getString("channelId");
                                }

                                case StreamingDataRequest.saveToWatchLaterDetailsName -> {
                                    return jsonBuilderString;
                                }
                            }
                        } catch (Exception e) {
                            Logger.printException(() -> "VideoDetailsRequest: ", e);
                        }
                    }
                }
            }
        }
        return null;
    }

    public boolean fetchStreamDetailsCompleted() {
        return future.isDone();
    }


    @Nullable
    public Object getStreamDetails() {
        try {
            return future.get(MAX_MILLISECONDS_TO_WAIT_FOR_FETCH, TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            Logger.printInfo(() -> "getStreamDetails timed out", ex);
        } catch (InterruptedException ex) {
            Logger.printException(() -> "getStreamDetails interrupted", ex);
            Thread.currentThread().interrupt(); // Restore interrupt status flag.
        } catch (ExecutionException ex) {
            Logger.printException(() -> "getStreamDetails failure", ex);
        }

        return null;
    }

    @NonNull
    @Override
    public String toString() {
        return "StreamingDataRequest{" + "videoId='" + videoId + '\'' + '}';
    }
}
